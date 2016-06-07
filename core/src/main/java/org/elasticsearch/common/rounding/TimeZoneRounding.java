/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.rounding;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.joda.time.DateTimeField;
import org.joda.time.DateTimeZone;

import java.io.IOException;

/**
 */
public abstract class TimeZoneRounding extends Rounding {

    public static Builder builder(DateTimeUnit unit) {
        return new Builder(unit);
    }

    public static Builder builder(TimeValue interval) {
        return new Builder(interval);
    }

    public static class Builder {

        private final DateTimeUnit unit;
        private final long interval;

        private DateTimeZone timeZone = DateTimeZone.UTC;

        private float factor = 1.0f;

        private long offset;

        public Builder(DateTimeUnit unit) {
            this.unit = unit;
            this.interval = -1;
        }

        public Builder(TimeValue interval) {
            this.unit = null;
            if (interval.millis() < 1)
                throw new IllegalArgumentException("Zero or negative time interval not supported");
            this.interval = interval.millis();
        }

        public Builder timeZone(DateTimeZone timeZone) {
            if (timeZone == null) {
                throw new IllegalArgumentException("Setting null as timezone is not supported");
            }
            this.timeZone = timeZone;
            return this;
        }

        public Builder offset(long offset) {
            this.offset = offset;
            return this;
        }

        public Builder factor(float factor) {
            this.factor = factor;
            return this;
        }

        public Rounding build() {
            Rounding timeZoneRounding;
            if (unit != null) {
                    timeZoneRounding = new TimeUnitRounding(unit, timeZone);
            } else {
                    timeZoneRounding = new TimeIntervalRounding(interval, timeZone);
            }
            if (offset != 0) {
                timeZoneRounding = new OffsetRounding(timeZoneRounding, offset);
            }
            if (factor != 1.0f) {
                timeZoneRounding = new FactorRounding(timeZoneRounding, factor);
            }
            return timeZoneRounding;
        }
    }

    static class TimeUnitRounding extends TimeZoneRounding {

        static final byte ID = 1;

        private DateTimeUnit unit;
        private DateTimeField field;
        private DateTimeZone timeZone;

        TimeUnitRounding() { // for serialization
        }

        TimeUnitRounding(DateTimeUnit unit, DateTimeZone timeZone) {
            this.unit = unit;
            this.field = unit.field(timeZone);
            this.timeZone = timeZone;
        }

        @Override
        public byte id() {
            return ID;
        }

        @Override
        public long roundKey(long utcMillis) {
            long rounded = field.roundFloor(utcMillis);
            if (timeZone.isFixed() == false && timeZone.getOffset(utcMillis) != timeZone.getOffset(rounded)) {
                // in this case, we crossed a time zone transition. In some edge cases this will
                // result in a value that is not a rounded value itself. We need to round again
                // to make sure. This will have no affect in cases where 'rounded' was already a proper
                // rounded value
                rounded = field.roundFloor(rounded);
            }
            assert rounded == field.roundFloor(rounded);
            return rounded;
        }

        @Override
        public long valueForKey(long time) {
            assert roundKey(time) == time;
            return time;
        }

        @Override
        public long nextRoundingValue(long utcMillis) {
            long floor = roundKey(utcMillis);
            // add one unit and round to get to next rounded value
            long next = roundKey(field.add(floor, 1));
            if (next == floor) {
                // in rare case we need to add more than one unit
                next = roundKey(field.add(floor, 2));
            }
            return next;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            unit = DateTimeUnit.resolve(in.readByte());
            timeZone = DateTimeZone.forID(in.readString());
            field = unit.field(timeZone);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeByte(unit.id());
            out.writeString(timeZone.getID());
        }

        @Override
        public String toString() {
            return "[" + timeZone + "][" + unit +"]";
        }
    }

    static class TimeIntervalRounding extends TimeZoneRounding {

        final static byte ID = 2;

        private long interval;
        private DateTimeZone timeZone;

        TimeIntervalRounding() { // for serialization
        }

        TimeIntervalRounding(long interval, DateTimeZone timeZone) {
            if (interval < 1)
                throw new IllegalArgumentException("Zero or negative time interval not supported");
            this.interval = interval;
            this.timeZone = timeZone;
        }

        @Override
        public byte id() {
            return ID;
        }

        @Override
        public long roundKey(long utcMillis) {
            long timeLocal = utcMillis;
            timeLocal = timeZone.convertUTCToLocal(utcMillis);
            long rounded = Rounding.Interval.roundValue(Rounding.Interval.roundKey(timeLocal, interval), interval);
            return timeZone.convertLocalToUTC(rounded, false);
        }

        @Override
        public long valueForKey(long time) {
            assert roundKey(time) == time;
            return time;
        }

        @Override
        public long nextRoundingValue(long time) {
            long timeLocal = time;
            timeLocal = timeZone.convertUTCToLocal(time);
            long next = timeLocal + interval;
            return timeZone.convertLocalToUTC(next, false);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            interval = in.readVLong();
            timeZone = DateTimeZone.forID(in.readString());
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(interval);
            out.writeString(timeZone.getID());
        }
    }
}