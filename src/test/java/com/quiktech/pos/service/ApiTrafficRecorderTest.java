package com.quiktech.pos.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTrafficRecorderTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T10:00:30Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final ApiTrafficRecorder recorder = new ApiTrafficRecorder(jdbc, clock);

    @Test
    void flushesOnlyCompletedMinutesIntoMinuteAndHourTables() {
        recorder.record("GET", "/api/users/me", 200, 30, null, null);
        recorder.record("GET", "/api/users/me", 200, 700, null, null);

        recorder.flushCompletedMinutes();
        verify(jdbc, never()).batchUpdate(anyString(), any(MapSqlParameterSource[].class));

        now.set(Instant.parse("2026-10-10T10:01:05Z"));
        recorder.flushCompletedMinutes();

        var minute = captureBatch("api_traffic_minutely").get(0);
        assertThat(minute.getValue("bucket")).isEqualTo(Timestamp.from(Instant.parse("2026-10-10T10:00:00Z")));
        assertThat(minute.getValue("count")).isEqualTo(2L);
        assertThat(minute.getValue("total")).isEqualTo(730L);
        assertThat(minute.getValue("max")).isEqualTo(700L);
        assertThat(minute.getValue("b0")).isEqualTo(1L);   // 30 ms ∈ (0, 50]
        assertThat(minute.getValue("b4")).isEqualTo(1L);   // 700 ms ∈ (500, 1000]
        var hour = captureBatch("api_traffic_hourly").get(0);
        assertThat(hour.getValue("bucket")).isEqualTo(Timestamp.from(Instant.parse("2026-10-10T10:00:00Z")));
    }

    @Test
    void attributesStoreRequestsToTheirBusinessAndCountsServerErrors() throws Exception {
        doAnswer(invocation -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("id")).thenReturn(12L);
            when(row.getLong("business_id")).thenReturn(3L);
            invocation.<RowCallbackHandler>getArgument(2).processRow(row);
            return null;
        }).when(jdbc).query(contains("FROM stores"), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        recorder.record("POST", "/api/stores/{storeId}/orders", 201, 40, null, 12L);
        recorder.record("GET", "/api/businesses/{businessId}/products", 500, 60, 3L, null);
        recorder.flushAll();

        var business = captureBatch("api_traffic_business_hourly");
        assertThat(business).hasSize(1);
        assertThat(business.get(0).getValue("business")).isEqualTo(3L);
        assertThat(business.get(0).getValue("count")).isEqualTo(2L);
        assertThat(business.get(0).getValue("errors")).isEqualTo(1L);
    }

    @Test
    void persistenceFailureDoesNotPropagate() {
        when(jdbc.batchUpdate(anyString(), any(MapSqlParameterSource[].class))).thenThrow(new IllegalStateException("db down"));
        recorder.record("GET", "/api/users/me", 200, 10, null, null);

        assertThatCode(recorder::flushAll).doesNotThrowAnyException();
    }

    private List<MapSqlParameterSource> captureBatch(String table) {
        var sql = ArgumentCaptor.forClass(String.class);
        var rows = ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).batchUpdate(sql.capture(), rows.capture());
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("INTO " + table + " ")) return List.of(rows.getAllValues().get(i));
        }
        throw new AssertionError("No batch written to " + table);
    }
}
