package nest.oudiabridge.mtr4;

import nest.oudiabridge.parser.OudiaParser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Associates an MTR4 vehicle with an OuDia train using its depot and estimated depot-exit time.
 *
 * The route timing estimate is based on MTR4's own path timing profile and the current dwell
 * counter. It is read-only and never changes MTR4 vehicle state.
 */
public final class OudiaDepotDepartureMatcher {
    public static final long TOLERANCE_MILLIS = 30_000L;
    private static final int SECONDS_PER_DAY = 86_400;
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private OudiaDepotDepartureMatcher() {
    }

    public static Result analyze(OudiaParser.ParseResult timetable,
                                 Mtr4RuntimeAccess.VehicleSnapshot vehicle,
                                 ZoneId zoneId) {
        if (timetable == null || vehicle == null) {
            return Result.failure("Timetable or vehicle is unavailable");
        }
        if (!same(timetable.routeName, vehicle.route())) {
            return Result.failure("Route name does not match");
        }
        if (vehicle.depotName() == null || vehicle.depotName().isBlank()) {
            return Result.failure("MTR4 depot name is unavailable");
        }
        if (!vehicle.onRoute()) {
            return Result.failure("Vehicle has not left the depot");
        }
        if (vehicle.estimatedDepartureMillis() <= 0) {
            return Result.failure("Route timing is unavailable; departure time cannot be estimated");
        }

        String depotName = normalize(vehicle.depotName());
        long estimatedMillis = vehicle.estimatedDepartureMillis();
        String estimatedText = DATE_TIME_FORMAT.format(Instant.ofEpochMilli(estimatedMillis).atZone(zoneId));
        List<Candidate> candidates = new ArrayList<>();

        for (int trainIndex = 0; trainIndex < timetable.trains.size(); trainIndex++) {
            OudiaParser.TrainData train = timetable.trains.get(trainIndex);
            FirstStop firstStop = findFirstStop(timetable, train);
            if (firstStop == null || !depotName.equals(normalize(firstStop.stationName()))) {
                continue;
            }

            Integer scheduledSeconds = firstStop.departureSeconds();
            if (scheduledSeconds == null) {
                continue;
            }

            long scheduledMillis = nearestDailyOccurrence(scheduledSeconds, estimatedMillis, zoneId);
            long deltaMillis = estimatedMillis - scheduledMillis;
            candidates.add(new Candidate(trainIndex,
                    train.direction == null || train.direction.isBlank() ? "(empty)" : train.direction,
                    firstStop.stationName(), formatScheduleTime(scheduledSeconds),
                    estimatedText, deltaMillis, Math.abs(deltaMillis) <= TOLERANCE_MILLIS));
        }

        candidates.sort(Comparator
                .comparingLong((Candidate candidate) -> Math.abs(candidate.deltaMillis()))
                .thenComparingInt(Candidate::trainIndex));

        long withinTolerance = candidates.stream().filter(Candidate::withinTolerance).count();
        String reason;
        if (candidates.isEmpty()) {
            reason = "No train starts at the MTR4 depot in this timetable";
        } else if (withinTolerance == 0) {
            reason = "No departure is within ±30 seconds of the estimated depot-exit time";
        } else if (withinTolerance == 1) {
            reason = "Exactly one train matches the depot and ±30-second departure window";
        } else {
            reason = "Multiple trains match the depot and ±30-second departure window";
        }

        return new Result(true, vehicle.depotName(), estimatedText, candidates.size(),
                (int) withinTolerance, List.copyOf(candidates.subList(0, Math.min(10, candidates.size()))), reason);
    }

    public static MatchResult match(OudiaParser.ParseResult timetable,
                                    Mtr4RuntimeAccess.VehicleSnapshot vehicle,
                                    ZoneId zoneId) {
        Result result = analyze(timetable, vehicle, zoneId);
        if (!result.available() || result.matchCount() == 0) {
            return MatchResult.noMatch(result.reason());
        }
        if (result.matchCount() > 1) {
            return MatchResult.ambiguous(result.matchCount());
        }

        Candidate candidate = result.topCandidates().stream()
                .filter(Candidate::withinTolerance)
                .findFirst()
                .orElse(null);
        return candidate == null
                ? MatchResult.noMatch("Matching candidate was not retained in diagnostics")
                : MatchResult.matched(candidate.trainIndex());
    }

    private static FirstStop findFirstStop(OudiaParser.ParseResult timetable,
                                           OudiaParser.TrainData train) {
        for (OudiaParser.StopTime stop : train.stops) {
            if (stop.stopType == OudiaParser.StopType.NONE
                    || stop.stationIndex < 0 || stop.stationIndex >= timetable.stations.size()) {
                continue;
            }

            String stationName = stationName(timetable, stop.stationIndex);
            Integer departure = parseOptional(stop.departureTime);
            if (departure == null) {
                departure = parseOptional(stop.arrivalTime);
            }
            return new FirstStop(stationName, departure);
        }
        return null;
    }

    private static Integer parseOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OudiaScheduleAnalyzer.parseTime(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long nearestDailyOccurrence(int scheduleSeconds, long estimatedMillis, ZoneId zoneId) {
        LocalDate estimatedDate = Instant.ofEpochMilli(estimatedMillis).atZone(zoneId).toLocalDate();
        long secondsOfDay = Math.floorMod(scheduleSeconds, SECONDS_PER_DAY);
        long bestMillis = 0;
        long bestDifference = Long.MAX_VALUE;

        for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
            LocalDate date = estimatedDate.plusDays(dayOffset);
            long candidateMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    + secondsOfDay * 1000L;
            long difference = Math.abs(estimatedMillis - candidateMillis);
            if (difference < bestDifference) {
                bestDifference = difference;
                bestMillis = candidateMillis;
            }
        }
        return bestMillis;
    }

    private static String stationName(OudiaParser.ParseResult timetable, int index) {
        // Depot matching is explicitly based on the OuDia station's own name.
        // Do not substitute an MTR station-name override here.
        return timetable.stations.get(index).name;
    }

    private static boolean same(String first, String second) {
        String normalizedFirst = normalize(first);
        return !normalizedFirst.isEmpty() && normalizedFirst.equals(normalize(second));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.split("\\|", 2)[0].trim()
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String formatScheduleTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                safeSeconds / 3600, safeSeconds % 3600 / 60, safeSeconds % 60);
    }

    public record Candidate(int trainIndex, String direction, String depotStation,
                            String scheduledDepartureText, String estimatedDepartureText,
                            long deltaMillis, boolean withinTolerance) {
        public String deltaText() {
            long seconds = Math.round(deltaMillis / 1000.0D);
            return (seconds > 0 ? "+" : "") + seconds + "s";
        }
    }

    public record Result(boolean available, String depotName, String estimatedDepartureText,
                         int candidateCount, int matchCount, List<Candidate> topCandidates,
                         String reason) {
        private static Result failure(String reason) {
            return new Result(false, "", "", 0, 0, List.of(), reason);
        }
    }

    public record MatchResult(Status status, int trainIndex, int matchCount, String reason) {
        private static MatchResult matched(int trainIndex) {
            return new MatchResult(Status.MATCHED, trainIndex, 1, "Matched");
        }

        private static MatchResult ambiguous(int count) {
            return new MatchResult(Status.AMBIGUOUS, -1, count, "Multiple matching trains");
        }

        private static MatchResult noMatch(String reason) {
            return new MatchResult(Status.NO_MATCH, -1, 0, reason);
        }
    }

    public enum Status {
        MATCHED,
        AMBIGUOUS,
        NO_MATCH
    }

    private record FirstStop(String stationName, Integer departureSeconds) {
    }
}
