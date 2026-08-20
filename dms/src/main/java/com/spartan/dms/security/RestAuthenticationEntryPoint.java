package com.spartan.dms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spartan.dms.exception.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

// BUG-M2 fix: without this, a request with no/invalid JWT to a protected
// endpoint fell through to Spring Security's default handling -- a bare
// 403 with no body -- instead of this app's standard
// {timestamp,status,error,message,path} JSON envelope (see
// GlobalExceptionHandler / ErrorResponse). This only changes what an
// UNAUTHENTICATED request gets back; it grants no additional access, and
// it does NOT run for AccessDeniedException (wrong role on a valid token)
// -- that's a separate concern already handled by
// GlobalExceptionHandler.handleAccessDenied() and is untouched by this class.
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                          HttpServletResponse response,
                          AuthenticationException authException) throws IOException, ServletException {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpServletResponse.SC_UNAUTHORIZED)
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message("Authentication is required to access this resource.")
                .path(request.getRequestURI())
                .build();

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
