package nest.oudiabridge.mtr4;

import nest.oudiabridge.parser.OudiaParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only comparison between an imported OuDia train and a manually supplied clock.
 * This class deliberately does not access or mutate MTR vehicle internals.
 */
public final class OudiaScheduleAnalyzer {
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private OudiaScheduleAnalyzer() {
    }

    public static Analysis analyze(OudiaParser.ParseResult timetable, int trainIndex,
                                   Mtr4RuntimeAccess.VehicleSnapshot vehicle, String clockText) {
        if (timetable == null || vehicle == null) {
            return Analysis.failure("Timetable or vehicle is unavailable");
        }
        if (trainIndex < 0 || trainIndex >= timetable.trains.size()) {
            return Analysis.failure("Train index is outside the timetable");
        }

        final int clockSeconds;
        try {
            clockSeconds = parseTime(clockText);
        } catch (IllegalArgumentException exception) {
            return Analysis.failure("Invalid current time: " + exception.getMessage());
        }

        OudiaParser.TrainData train = timetable.trains.get(trainIndex);
        List<TimedStop> stops = buildTimeline(timetable, train);
        String currentName = normalize(vehicle.station());
        String nextName = normalize(vehicle.nextStation());
        if (currentName.isEmpty() || nextName.isEmpty()) {
            return Analysis.failure("MTR4 did not provide both current and next station names");
        }

        for (int i = 0; i + 1 < stops.size(); i++) {
            TimedStop current = stops.get(i);
            TimedStop next = stops.get(i + 1);
            if (!currentName.equals(normalize(current.stationName()))
                    || !nextName.equals(normalize(next.stationName()))) {
                continue;
            }

            Integer referenceTime = current.departureSeconds() != null
                    ? current.departureSeconds() : current.arrivalSeconds();
            Integer targetTime = next.arrivalSeconds() != null
                    ? next.arrivalSeconds() : next.departureSeconds();
            if (referenceTime == null || targetTime == null) {
                return Analysis.failure("Matched station pair has no usable departure/arrival time");
            }

            int alignedClock = alignClockToSchedule(clockSeconds, referenceTime);
            int deviation = alignedClock - referenceTime;
            int remaining = targetTime - alignedClock;
            return Analysis.success(current.stationName(), next.stationName(),
                    referenceTime, targetTime, alignedClock, deviation, remaining,
                    next.stopType() == OudiaParser.StopType.STOP
                            ? vehicle.distanceToNextAtoStop() : Double.NaN,
                    vehicle.speedMetersPerMillisecond() * 1000);
        }

        return Analysis.failure("Bound train does not contain the vehicle's current/next station pair");
    }

    /**
     * Accepts OuDia HHmmss / HHmm values, and HH:mm:ss / HH:mm for command input.
     * Hours up to 47 are accepted for timetables that explicitly encode after-midnight times.
     */
    public static int parseTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("time is empty");
        }
        String text = value.trim();
        int hours;
        int minutes;
        int seconds = 0;
        try {
            if (text.indexOf(':') >= 0) {
                String[] parts = text.split(":", -1);
                if (parts.length < 2 || parts.length > 3) {
                    throw new IllegalArgumentException("expected HH:mm or HH:mm:ss");
                }
                hours = Integer.parseInt(parts[0]);
                minutes = Integer.parseInt(parts[1]);
                if (parts.length == 3) {
                    seconds = Integer.parseInt(parts[2]);
                }
            } else {
                if (!text.matches("\\d{4}|\\d{6}")) {
                    throw new IllegalArgumentException("expected HHmm or HHmmss");
                }
                hours = Integer.parseInt(text.substring(0, 2));
                minutes = Integer.parseInt(text.substring(2, 4));
                if (text.length() == 6) {
                    seconds = Integer.parseInt(text.substring(4, 6));
                }
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("contains non-numeric time fields");
        }
        if (hours < 0 || hours > 47 || minutes < 0 || minutes > 59
                || seconds < 0 || seconds > 59) {
            throw new IllegalArgumentException("hour must be 00-47 and minute/second 00-59");
        }
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static List<TimedStop> buildTimeline(OudiaParser.ParseResult timetable,
                                                  OudiaParser.TrainData train) {
        List<TimedStop> result = new ArrayList<>();
        int previous = -1;
        for (OudiaParser.StopTime stop : train.stops) {
            if (stop.stopType == OudiaParser.StopType.NONE) {
                continue;
            }
            String name = stationName(timetable, stop.stationIndex);
            Integer arrival = parseOptional(stop.arrivalTime);
            Integer departure = parseOptional(stop.departureTime);
            if (arrival != null) {
                arrival = advanceDay(arrival, previous);
                previous = arrival;
            }
            if (departure != null) {
                departure = advanceDay(departure, previous);
                previous = departure;
            }
            result.add(new TimedStop(name, stop.stopType, arrival, departure));
        }
        return result;
    }

    private static Integer parseOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parseTime(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int advanceDay(int time, int previous) {
        while (previous >= 0 && time < previous) {
            time += SECONDS_PER_DAY;
        }
        return time;
    }

    private static int alignClockToSchedule(int clockSeconds, int scheduleSeconds) {
        int best = clockSeconds;
        int bestDifference = Math.abs(clockSeconds - scheduleSeconds);
        for (int day = -2; day <= 2; day++) {
            int candidate = clockSeconds + day * SECONDS_PER_DAY;
            int difference = Math.abs(candidate - scheduleSeconds);
            if (difference < bestDifference) {
                best = candidate;
                bestDifference = difference;
            }
        }
        return best;
    }

    private static String stationName(OudiaParser.ParseResult timetable, int index) {
        if (index < 0 || index >= timetable.stations.size()) {
            return "";
        }
        if (index < timetable.syncConfig.stationMappings.size()) {
            String mapped = timetable.syncConfig.stationMappings.get(index).mtrStationName;
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        return timetable.stations.get(index).name;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String formatTime(int seconds) {
        int normalized = Math.max(0, seconds);
        int hours = normalized / 3600;
        int minutes = normalized % 3600 / 60;
        int remainder = normalized % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainder);
    }

    public record Analysis(boolean matched, String reason, String currentStation, String nextStation,
                           int scheduledDeparture, int scheduledArrival, int alignedClock,
                           int deviationSeconds, int secondsUntilArrival,
                           double distanceToNextStopMeters, double currentSpeedMetersPerSecond) {
        private static Analysis failure(String reason) {
            return new Analysis(false, reason, "", "", 0, 0, 0, 0, 0,
                    Double.NaN, Double.NaN);
        }

        private static Analysis success(String currentStation, String nextStation,
                                        int departure, int arrival, int clock,
                                        int deviation, int remaining, double distance,
                                        double currentSpeed) {
            return new Analysis(true, "", currentStation, nextStation, departure,
                    arrival, clock, deviation, remaining, distance, currentSpeed);
        }

        public boolean hasDistanceToNextStop() {
            return Double.isFinite(distanceToNextStopMeters) && distanceToNextStopMeters >= 0;
        }

        public boolean hasCurrentSpeed() {
            return Double.isFinite(currentSpeedMetersPerSecond) && currentSpeedMetersPerSecond >= 0;
        }

        public boolean hasRequiredAverageSpeed() {
            return hasDistanceToNextStop() && secondsUntilArrival > 0;
        }

        public double requiredAverageSpeedMetersPerSecond() {
            return hasRequiredAverageSpeed()
                    ? distanceToNextStopMeters / secondsUntilArrival : Double.NaN;
        }

        public String scheduledDepartureText() {
            return formatTime(scheduledDeparture);
        }

        public String scheduledArrivalText() {
            return formatTime(scheduledArrival);
        }

        public String alignedClockText() {
            return formatTime(alignedClock);
        }

        public String deviationText() {
            if (deviationSeconds == 0) return "on schedule";
            return (deviationSeconds > 0 ? "late " : "early ")
                    + Math.abs(deviationSeconds) + "s";
        }

        public String remainingText() {
            if (secondsUntilArrival == 0) return "due now";
            if (secondsUntilArrival > 0) return secondsUntilArrival + "s remaining";
            return Math.abs(secondsUntilArrival) + "s past scheduled arrival";
        }
    }

    private record TimedStop(String stationName, Integer arrivalSeconds, Integer departureSeconds) {
    }
}
