package com.research.assistant.service.async;

/** 可在服务重启后依据 task_type + context_json 重新构造的任务处理器。 */
@FunctionalInterface
public interface AsyncTaskHandler {

    Object execute(AsyncTaskExecutionContext context) throws Exception;
}
