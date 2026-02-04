package cn.flying.platformapi;

import cn.flying.platformapi.request.*;
import cn.flying.platformapi.response.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformApiJacksonRecordTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeAndDeserializeRequestRecords() throws Exception {
        StoreFileRequest storeFileRequest = new StoreFileRequest("u1", "f.txt", "{\"k\":1}", "content-json");
        StoreFileRequest storeFileRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(storeFileRequest), StoreFileRequest.class);
        assertThat(storeFileRequest2).isEqualTo(storeFileRequest);

        ShareFilesRequest shareFilesRequest = new ShareFilesRequest("u1", List.of("h1", "h2"), 60);
        ShareFilesRequest shareFilesRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(shareFilesRequest), ShareFilesRequest.class);
        assertThat(shareFilesRequest2).isEqualTo(shareFilesRequest);

        DeleteFilesRequest deleteFilesRequest = new DeleteFilesRequest("u1", List.of("h1"));
        DeleteFilesRequest deleteFilesRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(deleteFilesRequest), DeleteFilesRequest.class);
        assertThat(deleteFilesRequest2).isEqualTo(deleteFilesRequest);

        CancelShareRequest cancelShareRequest = new CancelShareRequest("SC123", "u1");
        CancelShareRequest cancelShareRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(cancelShareRequest), CancelShareRequest.class);
        assertThat(cancelShareRequest2).isEqualTo(cancelShareRequest);
    }

    @Test
    void shouldSerializeAndDeserializeResponseRecordsWithStableJsonFields() throws Exception {
        SharingVO sharingVO = new SharingVO(
                "u1",
                List.of("h1"),
                "SC123",
                10,
                9,
                1700000000000L,
                true
        );
        JsonNode sharingJson = objectMapper.readTree(objectMapper.writeValueAsBytes(sharingVO));
        assertThat(sharingJson.has("isValid")).isTrue();
        assertThat(sharingJson.get("isValid").asBoolean()).isTrue();
        SharingVO sharingVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(sharingVO), SharingVO.class);
        assertThat(sharingVO2).isEqualTo(sharingVO);

        TransactionVO tx = new TransactionVO(
                "0xabc",
                "1",
                "1",
                "abi",
                "0xfrom",
                "0xto",
                "input",
                "sig",
                "123",
                1700000000000L
        );
        JsonNode txJson = objectMapper.readTree(objectMapper.writeValueAsBytes(tx));
        assertThat(txJson.has("from")).isTrue();
        assertThat(txJson.has("to")).isTrue();
        assertThat(txJson.get("from").asText()).isEqualTo("0xfrom");
        assertThat(txJson.get("to").asText()).isEqualTo("0xto");
        TransactionVO tx2 = objectMapper.readValue(objectMapper.writeValueAsBytes(tx), TransactionVO.class);
        assertThat(tx2).isEqualTo(tx);

        FileVO fileVO = new FileVO("a.txt", "h1", 1L, 2L, "text/plain");
        FileVO fileVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(fileVO), FileVO.class);
        assertThat(fileVO2).isEqualTo(fileVO);

        FileDetailVO detailVO = new FileDetailVO("u1", "a.txt", "p", "c", "h1", "t", 2L, 1L, "text/plain");
        FileDetailVO detailVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(detailVO), FileDetailVO.class);
        assertThat(detailVO2).isEqualTo(detailVO);
    }
}
