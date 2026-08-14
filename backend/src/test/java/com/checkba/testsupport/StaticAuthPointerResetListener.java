package com.checkba.testsupport;

import com.checkba.controller.AuthController;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserSessionService;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 把 {@link AuthController} 的 JVM 级 static 服务指针钉回**当前测试上下文**的 bean。
 *
 * 背景：AuthController.getUserIdFromSession 是静态入口，依赖三个由服务构造器
 * 自注册的 static 指针。测试 JVM 里多个 Spring 上下文并存（TestContext 缓存），
 * 指针指向「最后创建的上下文」的 bean——于是任何测试的鉴权行为都随**类执行顺序**
 * 漂移：mac 与 Linux 的文件系统枚举顺序不同，就出现过本地全绿、CI 三连红
 * （IdorAuthIntegrationTest 被先起的 local-mode=true 上下文解析成同一个本机用户），
 * 修完又反向炸掉后跑的 local-mode 用例。
 *
 * 每个测试方法开跑前重新注册一次，Spring 系测试从此与顺序无关。
 * 非 Spring 的纯单元测试不经过 TestContext，触碰这些 static 时自行钉住
 * （见 DeviceTokenServiceTest）。
 */
public class StaticAuthPointerResetListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        // 尽量晚执行：排在依赖注入、@MockBean 装配之后，拿到的才是最终 bean。
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        ApplicationContext ctx = testContext.getApplicationContext();
        LocalIdentityService localIdentity = ctx.getBeanProvider(LocalIdentityService.class).getIfAvailable();
        if (localIdentity != null) {
            AuthController.registerLocalIdentityService(localIdentity);
        }
        UserSessionService userSession = ctx.getBeanProvider(UserSessionService.class).getIfAvailable();
        if (userSession != null) {
            AuthController.registerUserSessionService(userSession);
        }
        DeviceTokenService deviceToken = ctx.getBeanProvider(DeviceTokenService.class).getIfAvailable();
        if (deviceToken != null) {
            AuthController.registerDeviceTokenService(deviceToken);
        }
    }
}
