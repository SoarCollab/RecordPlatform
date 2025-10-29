package cn.flying.monitor.server.utils;

import cn.flying.monitor.server.entity.dto.RuntimeData;
import cn.flying.monitor.server.entity.vo.request.RuntimeDetailVO;
import cn.flying.monitor.server.entity.vo.response.RuntimeHistoryVO;
import com.alibaba.fastjson2.JSONObject;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @program: monitor
 * @description: influxDB工具类
 * @author: 王贝强
 * @create: 2024-07-16 16:54
 */
@Component
public class InfluxDBUtils {

    @Value("${spring.influx.url}")
    String url;

    @Value("${spring.influx.user}")
    String user;

    @Value("${spring.influx.password}")
    String password;

    @Value("${spring.influx.bucket}")
    String BUCKET;

    @Value("${spring.influx.organization}")
    String ORG;

    private InfluxDBClient client;

    @PostConstruct
    public void init() {
        client = InfluxDBClientFactory.create(url, user, password.toCharArray());
    }

    public void writeRuntimeData(int clientId, RuntimeDetailVO vo) {
        RuntimeData data = new RuntimeData();
        BeanUtils.copyProperties(vo, data);
        data.setClientId(clientId);
        data.setTimestamp(new Date(vo.getTimestamp()).toInstant());
        WriteApiBlocking writeApi = client.getWriteApiBlocking();
        writeApi.writeMeasurement(BUCKET, ORG, WritePrecision.NS, data);
    }

    public RuntimeHistoryVO readRuntimeHistory(int clientId) {
        RuntimeHistoryVO vo = new RuntimeHistoryVO();
        String q = """
                    from(bucket: "%s")
                      |> range(start: -1h)
                      |> filter(fn: (r) => r._measurement == "runtime")
                      |> filter(fn: (r) => r.clientId == "%s")
                      |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                      |> keep(columns: ["_time","cpuUsage","memoryUsage","diskUsage","networkUpload","networkDownload","diskRead","diskWrite"]) 
                """;
        String format = String.format(q, BUCKET, clientId);
        List<FluxTable> tables = client.getQueryApi().query(format, ORG);
        if (tables == null || tables.isEmpty()) return vo;
        for (FluxTable table : tables) {
            for (FluxRecord rec : table.getRecords()) {
                JSONObject object = new JSONObject();
                object.put("timestamp", rec.getTime());
                object.put("cpuUsage", rec.getValueByKey("cpuUsage"));
                object.put("memoryUsage", rec.getValueByKey("memoryUsage"));
                object.put("diskUsage", rec.getValueByKey("diskUsage"));
                object.put("networkUpload", rec.getValueByKey("networkUpload"));
                object.put("networkDownload", rec.getValueByKey("networkDownload"));
                object.put("diskRead", rec.getValueByKey("diskRead"));
                object.put("diskWrite", rec.getValueByKey("diskWrite"));
                vo.getList().add(object);
            }
        }
        return vo;
    }
}
