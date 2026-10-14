package com.kata.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T09:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private WarehouseDeskApp app;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        app = new WarehouseDeskApp(clock);
        app.seedData();
    }

    @Test
    void reserveHoldsStockWithoutRemovingIt() {
        app.processLine("RESERVE;erin;PEN-BLUE;5;30");

        assertEquals("ACTIVE", app.reservationStatus("R1"));
        assertEquals(5, app.reservedStock("PEN-BLUE"));
        assertEquals(20, app.availableStock("PEN-BLUE"));
    }

    @Test
    void reserveFailsWhenNotEnoughAvailableStock() {
        app.processLine("RESERVE;erin;STAPLER;5;30");

        assertNull(app.reservationStatus("R1"));
        assertEquals(0, app.reservedStock("STAPLER"));
        assertTrue(lastEvent().startsWith("cannot reserve 5 of STAPLER"));
    }

    @Test
    void reserveCountsExistingReservationsAgainstAvailability() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("RESERVE;frank;STAPLER;2;30");

        assertEquals("ACTIVE", app.reservationStatus("R1"));
        assertNull(app.reservationStatus("R2"));
        assertEquals(1, app.availableStock("STAPLER"));
    }

    @Test
    void reservedStockCannotBeSold() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("SELL;bob;STAPLER;2");

        assertEquals("BACKORDER", app.orderStatus("O1001"));
    }

    @Test
    void confirmShipsReservedStockAsOrder() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("CONFIRM;R1");

        assertEquals("CONFIRMED", app.reservationStatus("R1"));
        assertEquals("SHIPPED", app.orderStatus("O1001"));
        assertEquals(0, app.reservedStock("STAPLER"));
        assertEquals(1, app.availableStock("STAPLER"));
        assertEquals(336.0, app.cashBalance(), 0.001);
    }

    @Test
    void confirmedOrderCanBeCancelledWithRestock() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("CONFIRM;R1");
        app.processLine("CANCEL;O1001");

        assertEquals("CANCELLED_AFTER_SHIP", app.orderStatus("O1001"));
        assertEquals(4, app.availableStock("STAPLER"));
        assertEquals(300.0, app.cashBalance(), 0.001);
    }

    @Test
    void releaseReturnsStockToAvailability() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("RELEASE;R1");

        assertEquals("RELEASED", app.reservationStatus("R1"));
        assertEquals(0, app.reservedStock("STAPLER"));
        assertEquals(4, app.availableStock("STAPLER"));
    }

    @Test
    void releasedReservationCannotBeConfirmed() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("RELEASE;R1");
        app.processLine("CONFIRM;R1");

        assertNull(app.orderStatus("O1001"));
        assertEquals("cannot confirm R1 from state RELEASED", lastEvent());
    }

    @Test
    void confirmedReservationCannotBeReleased() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        app.processLine("CONFIRM;R1");
        app.processLine("RELEASE;R1");

        assertEquals(1, app.availableStock("STAPLER"));
        assertEquals("cannot release R1 from state CONFIRMED", lastEvent());
    }

    @Test
    void unknownReservationIsReported() {
        app.processLine("CONFIRM;R99");
        assertEquals("cannot confirm R99 because it does not exist", lastEvent());

        app.processLine("RELEASE;R99");
        assertEquals("cannot release R99 because it does not exist", lastEvent());
    }

    @Test
    void reservationStaysActiveBeforeExpiry() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        clock.advance(Duration.ofMinutes(29));

        assertEquals("ACTIVE", app.reservationStatus("R1"));
        assertEquals(1, app.availableStock("STAPLER"));
    }

    @Test
    void reservationExpiresAndReturnsStock() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        clock.advance(Duration.ofMinutes(30));
        app.processLine("COUNT;STAPLER");

        assertEquals("EXPIRED", app.reservationStatus("R1"));
        assertEquals(4, app.availableStock("STAPLER"));
        assertTrue(app.events().contains("reservation R1 expired, returned 3 of STAPLER"));
        assertEquals("count STAPLER onHand=4 reserved=0 available=4", lastEvent());
    }

    @Test
    void expiredReservationCannotBeConfirmed() {
        app.processLine("RESERVE;erin;STAPLER;3;30");
        clock.advance(Duration.ofMinutes(31));
        app.processLine("CONFIRM;R1");

        assertNull(app.orderStatus("O1001"));
        assertEquals("cannot confirm R1 from state EXPIRED", lastEvent());
    }

    @Test
    void expiredStockCanBeSoldAgain() {
        app.processLine("RESERVE;erin;STAPLER;4;10");
        clock.advance(Duration.ofMinutes(10));
        app.processLine("SELL;bob;STAPLER;4");

        assertEquals("SHIPPED", app.orderStatus("O1001"));
    }

    @Test
    void onlyDueReservationsExpire() {
        app.processLine("RESERVE;erin;PEN-BLUE;5;10");
        app.processLine("RESERVE;frank;PEN-BLUE;5;60");
        clock.advance(Duration.ofMinutes(15));

        assertEquals("EXPIRED", app.reservationStatus("R1"));
        assertEquals("ACTIVE", app.reservationStatus("R2"));
        assertEquals(5, app.reservedStock("PEN-BLUE"));
    }

    private String lastEvent() {
        return app.events().get(app.events().size() - 1);
    }
}
