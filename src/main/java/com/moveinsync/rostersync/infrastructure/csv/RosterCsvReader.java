package com.moveinsync.rostersync.infrastructure.csv;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

@Component
public class RosterCsvReader {
    public static final List<String> COLUMNS=List.of("employee_id","version","name","email","phone","manager_id","office_code","shift_code","status");
    private static final Map<String,EmployeeField> FIELDS=Map.of("name",EmployeeField.NAME,"email",EmployeeField.EMAIL,"phone",EmployeeField.PHONE,
            "manager_id",EmployeeField.MANAGER_ID,"office_code",EmployeeField.OFFICE_CODE,"shift_code",EmployeeField.SHIFT_CODE,"status",EmployeeField.STATUS);
    public List<String> header(Path path) throws IOException {
        try(var input=reader(path)) {
            var line=new StringBuilder();int character;
            while((character=input.read())!=-1 && character!='\n') {
                if(line.length()>=16384) throw new InvalidHeader();line.append((char)character);
            }
            try(var parsed=CSVFormat.DEFAULT.parse(new StringReader(line.toString()))) {
                var rows=parsed.iterator();if(!rows.hasNext()) throw new InvalidHeader();var names=rows.next().toList();
                if(!names.containsAll(COLUMNS) || new HashSet<>(names).size()!=names.size()) throw new InvalidHeader();
                return names;
            } catch(RuntimeException malformed) {throw new InvalidHeader();}
        }
    }
    public ReadSummary read(Path path,UUID tenantId,FieldOwnershipPolicy ownership,OfficeLookup offices,Consumer<Row> consumer) throws IOException {
        var validator=new RecordValidator(ownership,offices);long count=0,valid=0;Long trailer=null;
        try(var input=reader(path);var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(false).get().parse(input)) {
            var records=parser.iterator();
            while(records.hasNext()) {
                var record=records.next();long number=record.getRecordNumber();
                if(record.size()>0 && record.get(0).startsWith("#TRAILER") && !records.hasNext()) {
                    if(record.size()<2 || !record.get(1).matches("row_count=[0-9]{1,9}")) throw new MalformedCsv(number);
                    trailer=Long.parseLong(record.get(1).substring(10));continue;
                }
                count++;String id=value(record,"employee_id");long version=0;
                try {version=Long.parseLong(Objects.toString(value(record,"version"),""));}catch(NumberFormatException ignored) { }
                var fields=new EnumMap<EmployeeField,String>(EmployeeField.class);
                FIELDS.forEach((column,field)->fields.put(field,field==EmployeeField.PHONE?value(record,column):field.canonicalValue(value(record,column))));
                var checked=validator.validateFileRow(tenantId,id,version,fields,Set.of());
                var errors=new ArrayList<>(checked.errors());
                if(record.size()!=parser.getHeaderNames().size()) errors.add(new FieldError("record","INVALID_FORMAT"));
                String safeId=id!=null && id.matches("[A-Za-z0-9_-]{1,64}")?id:null;
                var kept=ownership.filter(WriteSource.HR_SNAPSHOT,fields).kept();
                var snapshot=new HrSnapshot(kept.get(EmployeeField.NAME),kept.get(EmployeeField.EMAIL),kept.get(EmployeeField.PHONE),kept.get(EmployeeField.MANAGER_ID),
                        kept.get(EmployeeField.OFFICE_CODE),kept.get(EmployeeField.SHIFT_CODE),kept.get(EmployeeField.STATUS));
                if(errors.isEmpty()) valid++;
                consumer.accept(new Row(number,safeId,version,errors.isEmpty()?snapshot:null,List.copyOf(errors)));
            }
        } catch(UncheckedIOException malformed) {throw new MalformedCsv(count+1);}
        catch(IllegalArgumentException malformed) {throw new MalformedCsv(count+1);}
        return new ReadSummary(count,valid,trailer);
    }
    private static String value(CSVRecord row,String key) {if(!row.isMapped(key) || !row.isSet(key)) return null;String value=row.get(key);return value.isEmpty()?null:value;}
    private static BufferedReader reader(Path path) throws IOException {
        var input=Files.newBufferedReader(path,StandardCharsets.UTF_8);input.mark(1);if(input.read()!=0xfeff) input.reset();return input;
    }
    public record Row(long number,String employeeId,long version,HrSnapshot snapshot,List<FieldError> errors) {
        @Override public String toString() {return "CsvRow[number="+number+"]";}
    }
    public record ReadSummary(long total,long valid,Long trailerCount) { }
    public static final class InvalidHeader extends IOException {public InvalidHeader(){super("INVALID_CSV_HEADER");}}
    public static final class MalformedCsv extends IOException {
        private final long number;
        public MalformedCsv(long number){super("MALFORMED_CSV");this.number=number;}
        public long number(){return number;}
    }
}
