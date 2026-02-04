package cn.flying.dao.vo;

import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
import cn.flying.dao.vo.system.ChainStatusVO;
import cn.flying.dao.vo.system.ComponentHealthVO;
import cn.flying.dao.vo.system.MonitorMetricsVO;
import cn.flying.dao.vo.system.SystemHealthVO;
import cn.flying.dao.vo.system.SystemStatsVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordJacksonCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void systemStats_roundTrip() throws Exception {
        SystemStatsVO vo = new SystemStatsVO(1L, 2L, 3L, 4L, 5L, 6L);

        String json = objectMapper.writeValueAsString(vo);
        SystemStatsVO restored = objectMapper.readValue(json, SystemStatsVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void chainStatus_roundTrip() throws Exception {
        ChainStatusVO vo = new ChainStatusVO(10L, 20L, 30L, 4, "LOCAL_FISCO", true, 1234567890L);

        String json = objectMapper.writeValueAsString(vo);
        ChainStatusVO restored = objectMapper.readValue(json, ChainStatusVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void componentHealth_roundTrip() throws Exception {
        ComponentHealthVO vo = new ComponentHealthVO("UP", Map.of("latencyMs", 12));

        String json = objectMapper.writeValueAsString(vo);
        ComponentHealthVO restored = objectMapper.readValue(json, ComponentHealthVO.class);

        assertThat(restored.status()).isEqualTo(vo.status());
        assertThat(restored.details()).containsEntry("latencyMs", 12);
    }

    @Test
    void systemHealth_roundTrip() throws Exception {
        SystemHealthVO vo = new SystemHealthVO(
                "UP",
                Map.of(
                        "database", new ComponentHealthVO("UP", Map.of()),
                        "redis", new ComponentHealthVO("DOWN", Map.of("error", "timeout"))
                ),
                42L,
                "2026-02-01T00:00:00"
        );

        String json = objectMapper.writeValueAsString(vo);
        SystemHealthVO restored = objectMapper.readValue(json, SystemHealthVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void monitorMetrics_roundTrip() throws Exception {
        MonitorMetricsVO vo = new MonitorMetricsVO(
                new SystemStatsVO(1L, 2L, 3L, 4L, 5L, 6L),
                new ChainStatusVO(10L, 20L, 30L, 4, "LOCAL_FISCO", true, 1234567890L),
                new SystemHealthVO("UP", Map.of(), 1L, "t")
        );

        String json = objectMapper.writeValueAsString(vo);
        MonitorMetricsVO restored = objectMapper.readValue(json, MonitorMetricsVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void shareAccessStats_roundTrip() throws Exception {
        ShareAccessStatsVO vo = new ShareAccessStatsVO("SC", 1L, 2L, 3L, 4L, 6L);

        String json = objectMapper.writeValueAsString(vo);
        ShareAccessStatsVO restored = objectMapper.readValue(json, ShareAccessStatsVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void shareAccessLog_roundTrip() throws Exception {
        Date now = new Date(1234567890L);
        ShareAccessLogVO vo = new ShareAccessLogVO(
                "1",
                "SC",
                2,
                ShareAccessLogVO.getActionTypeDesc(2),
                "U1",
                "alice",
                "127.0.0.1",
                "hash",
                "file.txt",
                now
        );

        String json = objectMapper.writeValueAsString(vo);
        ShareAccessLogVO restored = objectMapper.readValue(json, ShareAccessLogVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void fileDecryptInfo_roundTrip() throws Exception {
        FileDecryptInfoVO vo = new FileDecryptInfoVO(
                "k",
                "file.txt",
                123L,
                "text/plain",
                7,
                "hash"
        );

        String json = objectMapper.writeValueAsString(vo);
        FileDecryptInfoVO restored = objectMapper.readValue(json, FileDecryptInfoVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void userFileStats_roundTrip() throws Exception {
        UserFileStatsVO vo = new UserFileStatsVO(10L, 2048L, 3L, 1L);

        String json = objectMapper.writeValueAsString(vo);
        UserFileStatsVO restored = objectMapper.readValue(json, UserFileStatsVO.class);

        assertThat(restored).isEqualTo(vo);
    }

    @Test
    void fileProvenance_roundTrip() throws Exception {
        FileProvenanceVO vo = new FileProvenanceVO(
                "F1",
                "hash",
                "file.txt",
                false,
                "U0",
                "origin",
                "U1",
                "sharer",
                2,
                new Date(0),
                "SC",
                List.of(
                        new FileProvenanceVO.ProvenanceNode("U0", "origin", "F0", 0, null, new Date(0)),
                        new FileProvenanceVO.ProvenanceNode("U1", "sharer", "F1", 1, "SC", new Date(1))
                )
        );

        String json = objectMapper.writeValueAsString(vo);
        assertThat(json).contains("\"isOriginal\"");

        FileProvenanceVO restored = objectMapper.readValue(json, FileProvenanceVO.class);
        assertThat(restored).isEqualTo(vo);
    }
}
