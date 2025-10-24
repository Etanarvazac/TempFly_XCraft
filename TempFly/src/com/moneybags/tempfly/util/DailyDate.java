package com.moneybags.tempfly.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public class DailyDate {

    private final LocalDate date;

    public DailyDate(long millis) {
        this.date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DailyDate other = (DailyDate) o;
        return this.date.equals(other.date);
    }
}
