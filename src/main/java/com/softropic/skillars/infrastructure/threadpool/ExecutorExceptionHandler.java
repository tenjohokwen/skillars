package com.softropic.skillars.infrastructure.threadpool;

public class ExecutorExceptionHandler {

    private ExecutorExceptionHandler(){}

    static Exception handleException(Exception e, ClientThreadContext clientContext) {
        modifyStackTrace(e, clientContext);
        return e;

    }

    private static void modifyStackTrace(Exception e, ClientThreadContext clientThreadContext) {
        StackTraceElement[] stackTraceElements = mergeStacks(e.getStackTrace(), clientThreadContext.clientStack.getStackTrace(), clientThreadContext.clientThreadName);
        e.setStackTrace(stackTraceElements);
    }

    /**
     * combine stack traces making them appear as one.
     * This is part of the bigger effort to get full stack traces when exceptions are thrown in submitted tasks/runnables
     * @param currentStack the most recent stack
     * @param oldStack the stack got from the client
     * @param clientThreadName The name of the client thread
     * @return StackTraceElement[] merged stack trace
     */
    private static StackTraceElement[] mergeStacks(StackTraceElement[] currentStack, StackTraceElement[] oldStack, String clientThreadName) {
        StackTraceElement[] combined = new StackTraceElement[currentStack.length + oldStack.length + 1];
        System.arraycopy(currentStack, 0, combined, 0, currentStack.length);
        combined[currentStack.length] = new StackTraceElement("══════════════════════════", "<client call: Thread name:" + clientThreadName + ">", "", -1);
        System.arraycopy(oldStack, 0, combined, currentStack.length+1, oldStack.length);
        return combined;
    }
}
