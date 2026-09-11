package com.research.assistant.common;

/** 上传论文文件无法读取或不满足 PDF 要求时返回给客户端的明确错误。 */
public class PaperFileValidationException extends IllegalArgumentException {

    public PaperFileValidationException(String message) {
        super(message);
    }
}
