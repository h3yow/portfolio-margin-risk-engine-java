package com.github.h3yow.portfoliomarginriskenginejava.domain;

/**
 * 业务操作（资金划转 / 持仓调整）的处理结果。
 * duplicate=true 表示该请求为重复提交，返回首次处理的结论，本次未再次生效。
 */
public record OperationResult(String requestId,
                              boolean applied,
                              boolean duplicate,
                              long accountVersion,
                              String message) {
}
