package com.github.h3yow.portfoliomarginriskenginejava.ledger;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;

import java.util.List;

/** 整个账本在某个确定版本下的不可变快照。 */
public record LedgerSnapshot(long version, List<AccountSnapshot> accounts) {
}
