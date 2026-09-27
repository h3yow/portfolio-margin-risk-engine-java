package com.github.h3yow.portfoliomarginriskenginejava.web.dto;

public record CommandResponse(long version, boolean replayed, String summary) {
}
