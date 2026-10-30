package com.moveinsync.rostersync.infrastructure.csv;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.recon.FileRow;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

public class BucketSpillReader {
    public Bucket read(Path path,BiConsumer<Long,String> duplicate) throws IOException {
        var rows=new HashMap<String,FileRow>();var invalid=new HashSet<String>();
        var hashes=new HashMap<String,Map<Long,String>>();long extraValid=0;
        try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            while(input.available()>0) {
                long number=input.readLong();String id=string(input);long version=input.readLong();boolean valid=input.readBoolean();
                var versions=hashes.computeIfAbsent(id,key->new HashMap<>());boolean extra=!versions.isEmpty() || invalid.contains(id);
                if(extra){duplicate.accept(number,id);if(valid) extraValid++;}
                if(!valid){invalid.add(id);continue;}
                String hash=string(input);var values=new EnumMap<EmployeeField,String>(EmployeeField.class);
                for(var field:EmployeeField.values()) values.put(field,string(input));
                var snapshot=new HrSnapshot(values.get(EmployeeField.NAME),values.get(EmployeeField.EMAIL),values.get(EmployeeField.PHONE),values.get(EmployeeField.MANAGER_ID),
                        values.get(EmployeeField.OFFICE_CODE),values.get(EmployeeField.SHIFT_CODE),values.get(EmployeeField.STATUS),values.get(EmployeeField.PICKUP_POINT),values.get(EmployeeField.SPECIAL_ASSISTANCE));
                String previous=versions.putIfAbsent(version,hash);if(previous!=null && !previous.equals(hash)) invalid.add(id);
                var current=rows.get(id);if(current==null || version>current.version()) rows.put(id,new FileRow(id,version,hash,snapshot));
            }
        }
        invalid.forEach(rows::remove);return new Bucket(rows,invalid,extraValid);
    }
    private static String string(DataInputStream input) throws IOException {
        int length=input.readInt();if(length==-1)return null;if(length<0 || length>1048576)throw new IOException("INVALID_SPILL");
        byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException("INVALID_SPILL");return new String(bytes,StandardCharsets.UTF_8);
    }
    public record Bucket(Map<String,FileRow> rows,Set<String> presentButInvalid,long duplicateValidRows) { }
}
