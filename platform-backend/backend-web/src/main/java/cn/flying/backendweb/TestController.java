package cn.flying.backendweb;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: RecordPlatform
 * @description:
 * @author: 王贝强
 * @create: 2024-11-01 09:51
 */
@RestController
public class TestController {
    @RequestMapping("/")
    public String index(){
        return "hello world,I'm flying!!!";
    }
}
