package com.moveinsync.rostersync.application.outbox;
public final class SinkDispatchException extends RuntimeException {
    private final String sink;
    public SinkDispatchException(String sink,Throwable cause){super("SINK_DISPATCH_FAILED",cause);this.sink=sink;}
    public String sink(){return sink;}
}
