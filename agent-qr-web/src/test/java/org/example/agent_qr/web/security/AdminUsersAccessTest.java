package org.example.agent_qr.web.security;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.agent_qr.auth.controller.AdminController;
import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.auth.filter.JwtAuthenticationFilter;
import org.example.agent_qr.auth.handler.AbacAccessDeniedHandler;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.user.mapper.SysUserMapper;
import org.example.agent_qr.web.config.GlobalExceptionHandler;
import org.example.agent_qr.web.config.SecurityConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/users} 鉴权与口令外泄防护集成测试（批次 03 · 任务 3.3，问题 06 + 33 断裂 2）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li>接口无任何权限校验（路由仅 {@code authenticated()}），任何登录用户可批量拉取全站用户；</li>
 *   <li>响应体包含 BCrypt 口令哈希（{@code SysUser.password} 无 {@code @JsonIgnore}）；</li>
 *   <li>前端已传 {@code department}/{@code title} 但后端未声明，筛选被静默忽略。</li>
 * </ol>
 * </p>
 * <p>
 * 本测试装配真实的安全过滤器链（{@link SecurityConfig} + {@link JwtAuthenticationFilter}），
 * 用真实签发的 JWT 发起请求，而不是用 {@code @WithMockUser} 绕过鉴权——
 * 这样"普通用户被拒"这条用例拦截的才是真实的越权路径。
 * </p>
 *
 * @author agent-qr
 */
@WebMvcTest
@ContextConfiguration(classes = AdminUsersAccessTest.TestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=agent-qr-test-secret-key-0123456789-abcdefghij",
        "jwt.access-expiration=1800",
        "jwt.refresh-expiration=604800"
})
class AdminUsersAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SysUserMapper sysUserMapper;

    /**
     * 初始化 MyBatis-Plus 的 Lambda 列名缓存：
     * {@code LambdaQueryWrapper} 解析 {@code SysUser::getDepartment} → 列名依赖 TableInfo，
     * 切片测试不含 Mapper 扫描，需手工安装缓存（生产环境由 MyBatis-Plus 启动期完成）。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(SysUserMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @BeforeEach
    void resetMapper() {
        // 注册为普通 Bean 的 Mockito mock 不会被 Spring 自动重置，需手工清理调用记录，
        // 否则跨用例的调用记录会让 verify(never()) / verify(times(1)) 误判。
        Mockito.reset(sysUserMapper);
    }

    private SysUser userOf(String username, String role) {
        SysUser user = new SysUser();
        user.setId(role.equals("admin") ? 1L : 7L);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(1);
        user.setDepartment("HR");
        user.setClearanceLevel(2);
        user.setAllowedDomains("HR");
        user.setTitle("manager");
        return user;
    }

    private String tokenOf(String role) {
        return jwtUtil.generateAccessToken(userOf(role.equals("admin") ? "admin" : "chenming", role));
    }

    private SysUser pageRecord() {
        SysUser record = userOf("chenming", "user");
        record.setPassword("$2a$12$placeholder-hash-not-real");
        return record;
    }

    private void givenMapperReturnsOneRecord() {
        Page<SysUser> page = new Page<>(1, 10);
        page.setRecords(List.of(pageRecord()));
        page.setTotal(1);
        doReturn(page).when(sysUserMapper).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("★ 普通用户（role=user）访问用户列表返回 403，且响应体是统一 Result 结构")
    void listUsers_shouldReturn403_whenUserIsNotAdmin() throws Exception {
        givenMapperReturnsOneRecord();

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + tokenOf("user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 越权请求不得触达查询
        Mockito.verify(sysUserMapper, Mockito.never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("管理员访问用户列表返回 200")
    void listUsers_shouldReturn200_whenUserIsAdmin() throws Exception {
        givenMapperReturnsOneRecord();

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + tokenOf("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].username").value("chenming"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("★ 响应 JSON 中不包含 password 字段（这条用例能拦住口令外泄）")
    void listUsers_shouldNotExposePasswordField() throws Exception {
        givenMapperReturnsOneRecord();

        String body = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + tokenOf("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("未携带 Token 访问用户列表被拒绝（401/403）")
    void listUsers_shouldReject_whenAnonymous() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("★ department/title 查询条件真实生效（33 断裂 2：原先被 Spring 静默忽略）")
    void listUsers_shouldApplyDepartmentAndTitleFilters() throws Exception {
        givenMapperReturnsOneRecord();

        mockMvc.perform(get("/api/admin/users")
                        .param("department", "HR")
                        .param("title", "manager")
                        .header("Authorization", "Bearer " + tokenOf("admin")))
                .andExpect(status().isOk());

        AbstractWrapper<?, ?, ?> wrapper = captureWrapper();
        assertThat(wrapper.getTargetSql()).contains("department").contains("title");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("HR").contains("manager");
    }

    @Test
    @DisplayName("未传 department/title 时不产生对应过滤条件（筛选参数为可选）")
    void listUsers_shouldNotFilter_whenDepartmentAndTitleAbsent() throws Exception {
        givenMapperReturnsOneRecord();

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + tokenOf("admin")))
                .andExpect(status().isOk());

        AbstractWrapper<?, ?, ?> wrapper = captureWrapper();
        assertThat(wrapper.getTargetSql()).doesNotContain("department").doesNotContain("title");
    }

    @Test
    @DisplayName("keyword 模糊搜索仍生效（不得因新增筛选而丢失既有功能）")
    void listUsers_shouldKeepKeywordSearch() throws Exception {
        givenMapperReturnsOneRecord();

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "chen")
                        .header("Authorization", "Bearer " + tokenOf("admin")))
                .andExpect(status().isOk());

        AbstractWrapper<?, ?, ?> wrapper = captureWrapper();
        assertThat(wrapper.getTargetSql()).contains("username").contains("real_name");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("%chen%");
    }

    @Test
    @DisplayName("★ 任务 3.6.1：admin 可修改任意用户（原实现因『职级密级双高于』判定而不可用）")
    void updateUser_shouldAllow_whenAdminModifiesOtherUser() throws Exception {
        SysUser target = userOf("zhaoba", "user");
        target.setId(8L);
        Mockito.when(sysUserMapper.selectById(8L)).thenReturn(target);

        mockMvc.perform(put("/api/admin/users/8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenOf("admin"))
                        .content("{\"realName\":\"赵八\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Mockito.verify(sysUserMapper).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("★ 任务 3.6.2（防自提权）：admin 修改自己的职级被拒绝")
    void updateUser_shouldReject_whenAdminUpdatesOwnTitle() throws Exception {
        Mockito.when(sysUserMapper.selectById(1L)).thenReturn(userOf("admin", "admin"));

        mockMvc.perform(put("/api/admin/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenOf("admin"))
                        .content("{\"title\":\"director\"}"))
                .andExpect(jsonPath("$.code").value(403));

        Mockito.verify(sysUserMapper, Mockito.never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("★ 任务 3.6.2（防自提权）：admin 修改自己的角色/密级/可见域均被拒绝")
    void updateUser_shouldReject_whenAdminUpdatesOwnSensitiveFields() throws Exception {
        Mockito.when(sysUserMapper.selectById(1L)).thenReturn(userOf("admin", "admin"));

        for (String body : List.of("{\"role\":\"user\"}", "{\"clearanceLevel\":3}",
                "{\"allowedDomains\":\"HR,FINANCE,RD\"}", "{\"department\":\"FINANCE\"}")) {
            mockMvc.perform(put("/api/admin/users/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenOf("admin"))
                            .content(body))
                    .andExpect(jsonPath("$.code").value(403));
        }

        Mockito.verify(sysUserMapper, Mockito.never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("listUsers 方法必须标注 @PreAuthorize(\"hasRole('ADMIN')\")（纵深防御：不依赖路由规则）")
    void listUsers_shouldDeclarePreAuthorizeAdmin() throws Exception {
        Method listUsers = AdminController.class.getMethod("listUsers", Integer.class, Integer.class,
                String.class, String.class, String.class);

        PreAuthorize preAuthorize = listUsers.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).as("方法级鉴权不得缺失").isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }

    @SuppressWarnings("unchecked")
    private AbstractWrapper<?, ?, ?> captureWrapper() {
        ArgumentCaptor<Wrapper<SysUser>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(sysUserMapper).selectPage(any(Page.class), captor.capture());
        return (AbstractWrapper<?, ?, ?>) captor.getValue();
    }

    /**
     * 最小切片上下文：真实安全过滤器链 + 被测控制器（依赖以 mock 提供）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SecurityConfig.class)
    static class TestApplication {

        @Bean
        JwtUtil jwtUtil() {
            return new JwtUtil();
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter();
        }

        @Bean
        AbacAccessDeniedHandler abacAccessDeniedHandler() {
            return new AbacAccessDeniedHandler();
        }

        @Bean
        AbacEvaluator abacEvaluator() {
            return new AbacEvaluator();
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        SysUserMapper sysUserMapper() {
            return Mockito.mock(SysUserMapper.class);
        }

        @Bean
        AdminController adminController(SysUserMapper sysUserMapper, AbacEvaluator abacEvaluator) {
            return new AdminController(sysUserMapper, abacEvaluator);
        }
    }
}
