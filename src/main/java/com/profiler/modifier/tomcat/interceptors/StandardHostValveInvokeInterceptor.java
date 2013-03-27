package com.profiler.modifier.tomcat.interceptors;

import java.util.Enumeration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import com.profiler.common.AnnotationKey;
import com.profiler.common.ServiceType;
import com.profiler.context.*;
import com.profiler.interceptor.ByteCodeMethodDescriptorSupport;
import com.profiler.interceptor.MethodDescriptor;
import com.profiler.interceptor.StaticAroundInterceptor;
import com.profiler.interceptor.TraceContextSupport;
import com.profiler.logging.LoggingUtils;
import com.profiler.util.NetworkUtils;
import com.profiler.util.NumberUtils;

public class StandardHostValveInvokeInterceptor implements StaticAroundInterceptor, ByteCodeMethodDescriptorSupport, TraceContextSupport {

    private final Logger logger = Logger.getLogger(StandardHostValveInvokeInterceptor.class.getName());
    private final boolean isDebug = LoggingUtils.isDebug(logger);

    private MethodDescriptor descriptor;
	// private int apiId;
    private TraceContext traceContext;

    @Override
    public void before(Object target, String className, String methodName, String parameterDescription, Object[] args) {
        if (isDebug) {
            LoggingUtils.logBefore(logger, target, className, methodName, parameterDescription, args);
        }

        try {
            traceContext.getActiveThreadCounter().start();

            HttpServletRequest request = (HttpServletRequest) args[0];
            String requestURL = request.getRequestURI();
            String remoteAddr = request.getRemoteAddr();

            TraceID traceId = populateTraceIdFromRequest(request);
            DefaultTrace trace;
            if (traceId != null) {
                if (logger.isLoggable(Level.INFO)) {
                    logger.info("TraceID exist. continue trace. " + traceId);
                    logger.log(Level.FINE, "requestUrl:" + requestURL + ", remoteAddr:" + remoteAddr);
                }
                // trace = new Trace(nextTraceId);
                trace = new DefaultTrace(traceId);
                traceContext.attachTraceObject(trace);
            } else {
                trace = new DefaultTrace();
                if (logger.isLoggable(Level.INFO)) {
                    logger.info("TraceID not exist. start new trace. " + trace.getTraceId());
                    logger.log(Level.FINE, "requestUrl:" + requestURL + ", remoteAddr:" + remoteAddr);
                }
                traceContext.attachTraceObject(trace);
            }

            trace.markBeforeTime();

            trace.recordServiceType(ServiceType.TOMCAT);
            trace.recordRpcName(requestURL);

            int port = request.getServerPort();
            trace.recordEndPoint(request.getServerName() + ((port > 0) ? ":" + port : ""));
            trace.recordRemoteAddr(remoteAddr);
            
            // 서버 맵을 통계정보에서 조회하려면 remote로 호출되는 WAS의 관계를 알아야해서 부모의 application name을 전달받음.
            if (traceId != null && !traceId.isRoot()) {
            	String parentApplicationName = populateParentApplicationNameFromRequest(request);
            	short parentApplicationType = populateParentApplicationTypeFromRequest(request);
            	if (parentApplicationName != null) {
            		trace.recordParentApplication(parentApplicationName, parentApplicationType);
            		trace.recordAcceptorHost(NetworkUtils.getHostFromURL(request.getRequestURL().toString()));
            	}
            } else {
            	// TODO 여기에서 client 정보를 수집할 수 있다.
            }
        } catch (Exception e) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.log(Level.WARNING, "Tomcat StandardHostValve trace start fail. Caused:" + e.getMessage(), e);
            }
        }
    }

    @Override
    public void after(Object target, String className, String methodName, String parameterDescription, Object[] args, Object result) {
        if (isDebug) {
            LoggingUtils.logAfter(logger, target, className, methodName, parameterDescription, args, result);
        }

        traceContext.getActiveThreadCounter().end();
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }
        traceContext.detachTraceObject();

        HttpServletRequest request = (HttpServletRequest) args[0];
        String parameters = getRequestParameter(request);
        if (parameters != null && parameters.length() > 0) {
            trace.recordAttribute(AnnotationKey.HTTP_PARAM, parameters);
        }


        if (trace.getStackFrameId() != 0) {
            logger.warning("Corrupted CallStack found. StackId not Root(0)");
            // 문제 있는 callstack을 dump하면 도움이 될듯.
        }

        trace.recordApi(descriptor);
		// trace.recordApi(this.apiId);

        trace.recordException(result);

        trace.markAfterTime();
        trace.traceRootBlockEnd();
    }

    /**
     * Pupulate source trace from HTTP Header.
     *
     * @param request
     * @return
     */
    private TraceID populateTraceIdFromRequest(HttpServletRequest request) {
        String strUUID = request.getHeader(Header.HTTP_TRACE_ID.toString());
        if (strUUID != null) {
            UUID uuid = UUID.fromString(strUUID);
            int parentSpanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_PARENT_SPAN_ID.toString()), SpanID.NULL);
            int spanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_SPAN_ID.toString()), SpanID.NULL);
            boolean sampled = Boolean.parseBoolean(request.getHeader(Header.HTTP_SAMPLED.toString()));
            short flags = NumberUtils.parseShort(request.getHeader(Header.HTTP_FLAGS.toString()), (short) 0);

            TraceID id = new TraceID(uuid, parentSpanID, spanID, sampled, flags);
            if (logger.isLoggable(Level.INFO)) {
                logger.info("TraceID exist. continue trace. " + id);
            }
            return id;
        } else {
            return null;
        }
    }

	private String populateParentApplicationNameFromRequest(HttpServletRequest request) {
		return request.getHeader(Header.HTTP_PARENT_APPLICATION_NAME.toString());
	}
	
	private short populateParentApplicationTypeFromRequest(HttpServletRequest request) {
		String type = request.getHeader(Header.HTTP_PARENT_APPLICATION_TYPE.toString());
		if (type != null) {
			return Short.valueOf(type);
		}
		return ServiceType.UNDEFINED.getCode();
	}
    
    private String getRequestParameter(HttpServletRequest request) {
        Enumeration<?> attrs = request.getParameterNames();
        StringBuilder params = new StringBuilder();

        while (attrs.hasMoreElements()) {
            String keyString = attrs.nextElement().toString();
            Object value = request.getParameter(keyString);

            if (value != null) {
                String valueString = value.toString();
                int valueStringLength = valueString.length();

                if (valueStringLength > 0 && valueStringLength < 100) {
                    params.append(keyString).append("=").append(valueString);
                }

                if (attrs.hasMoreElements()) {
                    params.append(", ");
                }
            }
        }
        return params.toString();
    }

    @Override
    public void setMethodDescriptor(MethodDescriptor descriptor) {
        this.descriptor = descriptor;
        TraceContext traceContext = DefaultTraceContext.getTraceContext();
        traceContext.cacheApi(descriptor);
    }

    @Override
    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
