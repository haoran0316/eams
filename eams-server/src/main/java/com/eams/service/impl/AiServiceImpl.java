package com.eams.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.eams.dto.AssetPageQueryDTO;
import com.eams.entity.Asset;
import com.eams.entity.AssetApplication;
import com.eams.entity.AssetCategory;
import com.eams.entity.Employee;
import com.eams.mapper.AssetCategoryMapper;
import com.eams.mapper.AssetMapper;
import com.eams.mapper.EmployeeMapper;
import com.eams.service.AiService;
import com.eams.vo.AssetVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Spring AI 智能服务实现
 *
 * 说明：
 * 1. 依赖 spring-ai-starter-model-openai（Spring AI 2.0），默认对接 OpenAI 兼容接口，
 *    可通过环境变量 SKY_AI_BASE_URL / SKY_AI_API_KEY / SKY_AI_MODEL 切换 DeepSeek、Ollama、通义千问等。
 * 2. 三个方法均做了异常兜底：模型不可用 / 未配置时返回友好提示，不影响其它业务接口。
 * 3. 所有模型调用通过 CompletableFuture + 超时控制，避免线程长时间挂起。
 */
@Service
@Slf4j
public class AiServiceImpl implements AiService {

    private final ChatClient chatClient;
    private final AssetMapper assetMapper;
    private final EmployeeMapper employeeMapper;
    private final AssetCategoryMapper assetCategoryMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 虚拟线程池：与 Tomcat 线程池隔离，AI 调用卡住不影响其他接口
     */
    private static final ExecutorService AI_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 单次模型调用的超时时间
     */
    private static final Duration AI_TIMEOUT = Duration.ofSeconds(5);

    public AiServiceImpl(ChatClient.Builder chatClientBuilder,
                         AssetMapper assetMapper,
                         EmployeeMapper employeeMapper,
                         AssetCategoryMapper assetCategoryMapper) {
        this.chatClient = chatClientBuilder.build();
        this.assetMapper = assetMapper;
        this.employeeMapper = employeeMapper;
        this.assetCategoryMapper = assetCategoryMapper;
    }

    /**
     * 智能审批建议：把申请单信息（资产、数量、原因、申请人/部门）组装 Prompt，
     * 调用 ChatModel 生成审批建议（建议通过/拒绝及理由）。
     */
    @Override
    public String analyzeApplication(AssetApplication application) {
        if (application == null) {
            return "申请单为空，无法生成审批建议";
        }
        try {
            String assetName = "";
            if (application.getAssetId() != null) {
                Asset asset = assetMapper.getById(application.getAssetId());
                if (asset != null) {
                    assetName = asset.getName();
                }
            }
            String employeeName = "";
            String department = "";
            if (application.getEmployeeId() != null) {
                Employee employee = employeeMapper.getById(application.getEmployeeId());
                if (employee != null) {
                    employeeName = employee.getName();
                    department = employee.getDepartment();
                }
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("你是企业资产管理系统的智能审批助手，请根据以下资产申请单给出审批建议。\n");
            prompt.append("要求：先给结论（建议通过 / 建议拒绝），再给出理由（最多3条），控制在120字以内，使用中文。\n");
            prompt.append("申请单号：").append(nullToEmpty(application.getApplicationNo())).append("\n");
            prompt.append("申请人：").append(nullToEmpty(employeeName))
                    .append("（部门：").append(nullToEmpty(department)).append("）\n");
            prompt.append("申请资产：").append(nullToEmpty(assetName)).append("\n");
            prompt.append("申请数量：").append(application.getQuantity() == null ? "未填写" : application.getQuantity()).append("\n");
            prompt.append("申请原因：").append(nullToEmpty(application.getReason())).append("\n");

            String content = callWithTimeout(() ->
                    chatClient.prompt().user(prompt.toString()).call().content());
            return content == null || content.isBlank() ? "模型未返回内容，请人工审批" : content.trim();
        } catch (TimeoutException e) {
            log.warn("智能审批建议超时, applicationId={}", application.getId());
            return "AI 服务响应超时，请人工审批。";
        } catch (Exception e) {
            log.error("智能审批建议调用失败, applicationId={}", application.getId(), e);
            return "AI 服务暂不可用（请检查模型配置），请人工审批。";
        }
    }

    /**
     * 自然语言检索：让模型把自然语言解析为 JSON 查询条件（名称/编号/分类/状态），
     * 解析后调用 AssetMapper.pageQuery 检索资产列表。
     */
    @Override
    public List<AssetVO> searchByNaturalLanguage(String keyword) {
        List<AssetVO> result = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return result;
        }
        try {
            StringBuilder categoryHint = new StringBuilder();
            List<AssetCategory> categories = assetCategoryMapper.list();
            if (categories != null && !categories.isEmpty()) {
                StringJoiner joiner = new StringJoiner("，");
                for (AssetCategory c : categories) {
                    joiner.add(c.getId() + "=" + c.getName());
                }
                categoryHint.append("可用的资产分类映射：").append(joiner).append("\n");
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("你是企业资产管理系统中的自然语言查询解析器。\n");
            prompt.append("请把用户的自然语言查询转换为一个 JSON 对象，只允许包含以下字段：\n");
            prompt.append("- name：资产名称关键字（字符串，用户提到资产名称/类型时给出）\n");
            prompt.append("- code：资产编号关键字（字符串，用户提到编号时给出）\n");
            prompt.append("- categoryId：资产分类 id（数字，仅当用户描述的资产类别与下方分类映射匹配时给出）\n");
            prompt.append("- status：资产状态（数字：1在库、2已领用、3维修中、4报废，用户明确提到状态时给出）\n");
            prompt.append("- price：资产价值（数字，用户明确提到价值时给出）\n");
            if (categoryHint.length() > 0) {
                prompt.append(categoryHint);
            }
            prompt.append("用户查询：").append(keyword).append("\n");
            prompt.append("要求：只输出 JSON 对象，不要输出任何其他文字或解释。如果没有任何可提取条件，输出 {}");

            String content = callWithTimeout(() ->
                    chatClient.prompt().user(prompt.toString()).call().content());
            JsonNode node = parseJson(content);  // 把模型输出的内容,转为纯 JSON 对象
            if (node == null || node.isEmpty()) {
                return result;
            }

            AssetPageQueryDTO dto = new AssetPageQueryDTO();
            if (node.hasNonNull("name") && !node.get("name").asText().isEmpty()) {
                dto.setName(node.get("name").asText());
            }
            if (node.hasNonNull("code") && !node.get("code").asText().isEmpty()) {
                dto.setCode(node.get("code").asText());
            }
            if (node.hasNonNull("categoryId") && node.get("categoryId").canConvertToInt()) {
                dto.setCategoryId(node.get("categoryId").asLong());
            }
            if (node.hasNonNull("status") && node.get("status").canConvertToInt()) {
                dto.setStatus(node.get("status").asInt());
            }
            if (node.hasNonNull("price") && node.get("price").canConvertToInt()) {
                dto.setPrice(node.get("price").asInt());
            }
            dto.setPage(1);
            dto.setPageSize(20);

            PageHelper.startPage(dto.getPage(), dto.getPageSize());
            Page<AssetVO> page = assetMapper.pageQuery(dto);
            return page == null ? result : page.getResult();
        } catch (TimeoutException e) {
            log.warn("自然语言检索超时, keyword={}", keyword);
            return result;
        } catch (Exception e) {
            log.error("自然语言检索失败, keyword={}", keyword, e);
            return result;
        }
    }

    /**
     * 图片识别：把图片 URL 传给多模态模型，返回资产名称、分类、规格、描述等结构化信息。
     */
    @Override
    public Object recognizeAsset(String imageUrl) {
        Map<String, Object> result = new HashMap<>();
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            result.put("error", "图片地址不能为空");
            return result;
        }
        try {
            String prompt = "请识别这张图片中的资产（办公/设备类物品）。\n"
                    + "请以 JSON 格式输出以下字段：\n"
                    + "- name：资产名称\n"
                    + "- category：资产分类（如：电脑设备、办公家具、仪器设备等）\n"
                    + "- spec：规格型号（如无法判断则省略）\n"
                    + "- description：简短描述\n"
                    + "只输出 JSON，不要输出任何其他文字。";
            String content = callWithTimeout(() ->
                    chatClient.prompt()
                            .user(u -> u.text(prompt).media(new Media(mimeTypeOf(imageUrl), URI.create(imageUrl))))
                            .call()
                            .content());
            JsonNode node = parseJson(content); // 把模型输出的内容,转为纯 JSON 对象
            if (node == null) {
                result.put("error", "模型未返回有效识别结果");
                result.put("raw", content);
                return result;
            }
            result.put("name", textOrNull(node, "name"));
            result.put("category", textOrNull(node, "category"));
            result.put("spec", textOrNull(node, "spec"));
            result.put("description", textOrNull(node, "description"));
            result.put("imageUrl", imageUrl);
            return result;
        } catch (TimeoutException e) {
            log.warn("图片识别超时, imageUrl={}", imageUrl);
            result.put("error", "AI 服务响应超时，请稍后重试");
            return result;
        } catch (Exception e) {
            log.error("图片识别失败, imageUrl={}", imageUrl, e);
            result.put("error", "AI 服务暂不可用（请检查模型配置或图片地址）");
            return result;
        }
    }

    // ========== 超时控制 ==========

    /**
     * 在虚拟线程中异步调用模型，带超时控制。
     * 超时抛出 TimeoutException，由各业务方法自行处理降级。
     */
    private String callWithTimeout(Supplier<String> task) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(task, AI_EXECUTOR);
        return future.get(AI_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    // ---------------- 私有工具方法 ----------------

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * 从模型输出中提取 JSON 对象（兼容 `json ... ` 代码块及前后多余文字）
     */
    private JsonNode parseJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("`")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("`");
            if (first >= 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("解析模型输出 JSON 失败: {}", content);
            return null;
        }
    }

    /**
     * 根据图片地址后缀推断 MIME 类型，默认 JPEG
     */
    private MimeType mimeTypeOf(String imageUrl) {
        String lower = imageUrl.toLowerCase();
        if (lower.contains(".png") || lower.contains("image/png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (lower.contains(".webp") || lower.contains("image/webp")) {
            return MimeType.valueOf("image/webp");
        }
        if (lower.contains(".gif") || lower.contains("image/gif")) {
            return MimeTypeUtils.IMAGE_GIF;
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }
}
