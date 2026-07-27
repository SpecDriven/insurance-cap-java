package com.acme.insurance.domain;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Unique, human-readable policy and claim numbers backed by database
 * sequences (created by Liquibase, see db/changelog).
 */
@Component
public class NumberRanges {

    private final JdbcTemplate jdbc;

    public NumberRanges(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public String nextPolicyNumber() {
        Long next = jdbc.queryForObject("select next value for POLICY_NUMBER_SEQ", Long.class);
        return "PA-" + next;
    }

    public String nextClaimNumber() {
        Long next = jdbc.queryForObject("select next value for CLAIM_NUMBER_SEQ", Long.class);
        return "CLM-" + next;
    }
}
