package cn.zhixing.travel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 仅 infra 模式启用：允许真实业务依赖联调，明确拒绝尚未配置的模型请求。 */
@Component
@Profile("infra")
public class InfrastructureModelGuard extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/travel/") && !"OPTIONS".equals(request.getMethod())) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"message\":\"当前为中间件联调模式，模型服务尚未配置\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
