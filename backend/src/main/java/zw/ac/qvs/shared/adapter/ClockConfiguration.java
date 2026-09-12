package zw.ac.qvs.shared.adapter;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one {@link Clock} the application reads time from.
 *
 * <p>Nothing in the domain calls {@code LocalDate.now()} directly. Award dates, accreditation
 * windows, key validity and ledger timestamps are all time-sensitive, and a test that cannot
 * control the clock is a test that will eventually fail on a date boundary.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
