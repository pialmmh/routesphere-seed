package com.telcobright.seed.campaign.jdbc;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignPolicy;
import com.telcobright.seed.campaign.api.Creative;
import com.telcobright.seed.campaign.api.MediaKind;
import com.telcobright.seed.campaign.api.Targeting;
import com.telcobright.seed.campaign.api.TimeBand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one kind's campaigns out of routesphere's schema in four queries: the rows (with their status name and schedule
 * window), the time bands of their policies, their targets, their creatives. Terminal campaigns are returned too — the
 * rule, not the store, decides what may run.
 */
final class CampaignRowReader {

    private final Dialect dialect;
    private final String timeBands;

    CampaignRowReader(Dialect dialect) {
        this.dialect = dialect;
        this.timeBands = "SELECT policy_id, " + dialect.quote("day") + " AS band_day, specific_date_only, start_time, end_time, allow_or_restrict FROM time_band";
    }

    private static final String CAMPAIGNS = """
        SELECT c.CAMPAIGN_ID, c.CAMPAIGN_NAME, c.CAMPAIGN_TYPE, s.Type AS status_name, c.ID_PARTNER, c.EXPIRE_AT, c.PRIORITY,
               c.TOTAL_TASK_COUNT, c.SENT_TASK_COUNT, c.FAILED_TASK_COUNT, c.PENDING_TASK_COUNT, c.POLICY_ID, c.MESSAGE,
               c.AUDIO_FILE_PATH, c.AUDIO_FILE_NAME, c.FIELD1, c.FIELD2, c.FIELD3, c.FIELD4, c.FIELD5, c.EXTERNAL_CAMPAIGN_ID,
               sp.START_TIME AS schedule_start, sp.END_TIME AS schedule_end
        FROM campaign c
        LEFT JOIN enumjobstatus s ON s.id = c.STATUS
        LEFT JOIN schedule_policy sp ON sp.ID = c.SCHEDULE_POLICY_ID
        WHERE c.CAMPAIGN_TYPE = ?
        ORDER BY c.CAMPAIGN_ID""";
    private static final String TARGETS = "SELECT campaign_id, dimension, target_value FROM campaign_target";
    private static final String CREATIVES = "SELECT campaign_id, creative_id, kind, media_ref, duration_sec, click_url, caption FROM campaign_creative WHERE active = 1 ORDER BY id";

    List<Campaign> read(Connection c, String tenantId, CampaignKind kind) throws SQLException {
        Map<Integer, List<TimeBand>> bands = timeBands(c, timeBands);
        Map<Integer, Targeting> targets = targets(c);
        Map<Integer, List<Creative>> creatives = creatives(c);
        List<Campaign> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(CAMPAIGNS)) {
            ps.setString(1, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(campaign(rs, tenantId, kind, bands, targets, creatives));
            }
        }
        return out;
    }

    private static Campaign campaign(ResultSet rs, String tenantId, CampaignKind kind, Map<Integer, List<TimeBand>> bands,
                                     Map<Integer, Targeting> targets, Map<Integer, List<Creative>> creatives) throws SQLException {
        int id = rs.getInt("CAMPAIGN_ID");
        Integer policyId = (Integer) rs.getObject("POLICY_ID");
        Integer cap = (Integer) rs.getObject("FIELD1");
        Integer viewSeconds = (Integer) rs.getObject("FIELD2");
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "message", rs.getString("MESSAGE"));
        put(fields, "audioFilePath", rs.getString("AUDIO_FILE_PATH"));
        put(fields, "audioFileName", rs.getString("AUDIO_FILE_NAME"));
        put(fields, "field3", rs.getString("FIELD3"));
        put(fields, "field4", rs.getString("FIELD4"));
        put(fields, "field5", rs.getString("FIELD5"));
        put(fields, "externalCampaignId", rs.getString("EXTERNAL_CAMPAIGN_ID"));
        List<Creative> cr = creatives.getOrDefault(id, List.of());
        if (cr.isEmpty() && rs.getString("AUDIO_FILE_PATH") != null) {
            // a campaign written the voice way: the one pointer column is its only creative
            cr = List.of(new Creative("c" + id, kind == CampaignKind.VOICE ? MediaKind.AUDIO : MediaKind.VIDEO,
                rs.getString("AUDIO_FILE_PATH"), 0, null, rs.getString("MESSAGE")));
        }
        return new Campaign(id, tenantId, rs.getString("CAMPAIGN_NAME"), kind, rs.getString("status_name"),
            rs.getInt("ID_PARTNER"), instant(rs.getTimestamp("EXPIRE_AT")), instant(rs.getTimestamp("schedule_start")),
            instant(rs.getTimestamp("schedule_end")), rs.getInt("PRIORITY"), rs.getInt("TOTAL_TASK_COUNT"),
            rs.getInt("SENT_TASK_COUNT"), rs.getInt("FAILED_TASK_COUNT"), rs.getInt("PENDING_TASK_COUNT"),
            viewSeconds == null ? 0 : viewSeconds,
            new CampaignPolicy(policyId == null ? List.of() : bands.getOrDefault(policyId, List.of()), cap),
            targets.getOrDefault(id, Targeting.ANY), cr, fields);
    }

    private static Map<Integer, List<TimeBand>> timeBands(Connection c, String sql) throws SQLException {
        Map<Integer, List<TimeBand>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp specific = rs.getTimestamp("specific_date_only");
                Time start = rs.getTime("start_time");
                Time end = rs.getTime("end_time");
                out.computeIfAbsent(rs.getInt("policy_id"), k -> new ArrayList<>()).add(new TimeBand(rs.getString("band_day"),
                    specific == null ? null : specific.toLocalDateTime().toLocalDate(),
                    start == null ? java.time.LocalTime.MIN : start.toLocalTime(),
                    end == null ? java.time.LocalTime.MAX : end.toLocalTime(),
                    rs.getBoolean("allow_or_restrict")));
            }
        }
        return out;
    }

    private static Map<Integer, Targeting> targets(Connection c) throws SQLException {
        Map<Integer, Map<String, Set<String>>> raw = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(TARGETS); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) raw.computeIfAbsent(rs.getInt("campaign_id"), k -> new HashMap<>())
                .computeIfAbsent(rs.getString("dimension"), k -> new HashSet<>()).add(rs.getString("target_value"));
        }
        Map<Integer, Targeting> out = new HashMap<>();
        raw.forEach((id, m) -> out.put(id, new Targeting(m)));
        return out;
    }

    private static Map<Integer, List<Creative>> creatives(Connection c) throws SQLException {
        Map<Integer, List<Creative>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(CREATIVES); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.computeIfAbsent(rs.getInt("campaign_id"), k -> new ArrayList<>()).add(new Creative(
                rs.getString("creative_id"), MediaKind.of(rs.getString("kind")), rs.getString("media_ref"),
                rs.getInt("duration_sec"), rs.getString("click_url"), rs.getString("caption")));
        }
        return out;
    }

    private static void put(Map<String, String> m, String k, String v) { if (v != null) m.put(k, v); }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
