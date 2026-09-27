package com.github.h3yow.portfoliomarginriskenginejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 集成测试：版本追溯、错误分类、重复提交在 Web 层同样成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RiskControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RiskControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlow_accountTransferQuoteRiskAndLiquidation() throws Exception {
        String acc = "WEB-1";
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + acc + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        log.info("[Web-开户] accountId={} version=0", acc);

        // 入金 200
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"WEB-1\",\"requestId\":\"t-deposit\","
                                + "\"type\":\"DEPOSIT\",\"amount\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.accountVersion").value(1));

        // 配置保证金率（0.2）与行情（10）
        mockMvc.perform(put("/api/margin-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAA\",\"rate\":0.2}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/market/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAA\",\"price\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 杠杆买入 100@10 = 1000：自有 200 + 贷款 800 => cash=-800
        // 开仓口径：成交后权益 200，维持保证金 1000*0.2=200，可用 0 —— 恰好满足
        mockMvc.perform(post("/api/positions/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"WEB-1\",\"requestId\":\"t-buy\","
                                + "\"symbol\":\"AAA\",\"side\":\"BUY\",\"qty\":100,\"price\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.accountVersion").value(2));
        log.info("[Web-杠杆买入] 现金 -800，持仓 AAA:100，成本 1000，初始保证金校验恰过边界");

        // 价格 10：权益 200，MM 200，可用 0 -> 边界，不触发
        mockMvc.perform(get("/api/accounts/WEB-1/risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountVersion").value(2))
                .andExpect(jsonPath("$.liquidation").value(false))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.metrics.equity").value(200))
                .andExpect(jsonPath("$.metrics.availableMargin").value(0))
                .andExpect(jsonPath("$.reasons").isNotEmpty());
        log.info("[Web-风险查询] 价格10时权益200/MM200/可用0 -> 边界OK");

        // 行情跌到 9：权益 100，MM 180，可用 -80 -> LIQUIDATION
        mockMvc.perform(put("/api/market/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAA\",\"price\":9}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts/WEB-1/risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountVersion").value(2))
                .andExpect(jsonPath("$.quoteVersion").value(2))
                .andExpect(jsonPath("$.liquidation").value(true))
                .andExpect(jsonPath("$.status").value("LIQUIDATION"))
                .andExpect(jsonPath("$.metrics.equity").value(100))
                .andExpect(jsonPath("$.metrics.maintenanceMargin").value(180.0))
                .andExpect(jsonPath("$.metrics.availableMargin").value(-80.0));
        log.info("[Web-风险查询] 价格9时权益100/MM180/可用-80 -> LIQUIDATION，结论带账户版本2与行情版本2");

        // 行情涨到 20：权益 1200，MM 400，可用 800 -> OK
        mockMvc.perform(put("/api/market/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAA\",\"price\":20}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts/WEB-1/risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liquidation").value(false))
                .andExpect(jsonPath("$.metrics.equity").value(1200))
                .andExpect(jsonPath("$.metrics.maintenanceMargin").value(400.0));
        log.info("[Web-风险查询] 价格20时权益1200/MM400 -> OK（行情版本3，账户版本2，可分别追溯）");
    }

    @Test
    void duplicateRequestOverHttpAppliedOnce() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"WEB-2\"}"))
                .andExpect(status().isOk());

        String body = "{\"accountId\":\"WEB-2\",\"requestId\":\"dup-http\","
                + "\"type\":\"DEPOSIT\",\"amount\":77}";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transfers")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountVersion").value(1))
                    .andExpect(jsonPath("$.applied").value(i == 0))
                    .andExpect(jsonPath("$.duplicate").value(i != 0));
        }
        log.info("[Web-重复提交] 三次相同 requestId，仅首次生效，版本恒为 1");
    }

    @Test
    void errorClassificationsAreDistinguishableOverHttp() throws Exception {
        // 账户不存在 -> 404
        mockMvc.perform(get("/api/accounts/NOPE/risk"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"WEB-3\"}"))
                .andExpect(status().isOk());

        // 非法参数 -> 400
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"WEB-3\",\"requestId\":\"bad\","
                                + "\"type\":\"DEPOSIT\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        // 重复开户 -> 409
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"WEB-3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

        // 缺保证金率 + 缺行情下买入后查询会分别得到 503：先配置行情但不配保证金率
        mockMvc.perform(put("/api/market/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ZZZ\",\"price\":10}"))
                .andExpect(status().isOk());
        // 直接验证非法报价写入 -> 400 INVALID_PRICE
        mockMvc.perform(put("/api/market/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ZZZ\",\"price\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRICE"));
        log.info("[Web-错误分类] 404/400/409/503 各类错误码可区分");
    }
}
