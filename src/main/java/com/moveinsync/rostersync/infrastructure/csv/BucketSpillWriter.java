package com.moveinsync.rostersync.infrastructure.csv;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.recon.BucketPartitioner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class BucketSpillWriter implements AutoCloseable {
    private final DataOutputStream[] streams;
    private final RecordHasher hasher;
    public BucketSpillWriter(Path directory,int buckets,RecordHasher hasher) throws IOException {
        this.hasher=hasher;streams=new DataOutputStream[buckets];Files.createDirectories(directory);
        try {for(int index=0;index<buckets;index++) streams[index]=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file(directory,index)),8192));}
        catch(IOException failure){close();throw failure;}
    }
    public void write(RosterCsvReader.Row row) throws IOException {
        if(row.employeeId()==null) return;
        var output=streams[BucketPartitioner.bucketOf(row.employeeId(),streams.length)];
        output.writeLong(row.number());string(output,row.employeeId());output.writeLong(row.version());output.writeBoolean(row.snapshot()!=null);
        if(row.snapshot()!=null) {
            string(output,hasher.hash(row.snapshot()));for(var field:EmployeeField.values()) string(output,row.snapshot().get(field));
        }
    }
    static void string(DataOutputStream output,String value) throws IOException {
        if(value==null){output.writeInt(-1);return;}var bytes=value.getBytes(StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);
    }
    public static Path file(Path directory,int bucket){return directory.resolve("b-%03d.bin".formatted(bucket));}
    public void close() throws IOException {IOException failed=null;for(var stream:streams) if(stream!=null) try{stream.close();}catch(IOException error){failed=error;}if(failed!=null) throw failed;}
}
