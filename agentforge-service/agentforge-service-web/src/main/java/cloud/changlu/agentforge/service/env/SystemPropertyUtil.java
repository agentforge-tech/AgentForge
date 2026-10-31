package cloud.changlu.agentforge.service.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 启动目录解析工具：将 conf / logs / upload / temp 等外部目录解析为系统属性，供 application.yml 与 logback.xml 以 {@code
 * ${user.dir.xxx}/} 方式引用。
 *
 * <p>先解析出「服务主目录」（包含 {@code conf} 目录的那一层），所有子目录均挂在其下，保证日志与配置
 * 始终同处一地。为兼容多种启动方式（仓库根目录、agentforge-service 目录、模块目录或 IDE 直接运行）， 服务主目录按以下顺序探测：当前工作目录 → 当前工作目录下的
 * agentforge-service → 上级目录。
 *
 * @author changlu
 * @since 2026-10-14
 */
public final class SystemPropertyUtil {

    private static final Logger log = LoggerFactory.getLogger(SystemPropertyUtil.class);

    private static final String SERVICE_DIR = "agentforge-service";
    private static final String CONF_DIR = "conf";

    private static File serviceHome;

    private SystemPropertyUtil() {}

    public static void setSystemUserDir() {
        setConfDir();
        setLogDir();
        setDataDir();
        setLocalUploadFileDir();
        setTempFileDir();
    }

    /** 配置文件目录。 */
    public static void setConfDir() {
        setCustomDir(CONF_DIR);
    }

    /** 日志目录。 */
    public static void setLogDir() {
        setCustomDir("logs");
    }

    /** 本地数据目录（SQLite 数据库文件等）。 */
    public static void setDataDir() {
        setCustomDir("data");
    }

    /** 本地文件上传目录。 */
    public static void setLocalUploadFileDir() {
        setCustomDir("upload");
    }

    /** 临时文件目录。 */
    public static void setTempFileDir() {
        setCustomDir("temp");
    }

    /** 解析 {@code dirName} 目录并写入系统属性 {@code user.dir.<dirName>}（绝对路径，以 / 结尾）。 */
    public static void setCustomDir(String dirName) {
        File resolved = new File(serviceHome(), dirName);
        if (!resolved.exists() && !resolved.mkdirs()) {
            log.warn("Failed to create {} directory: {}", dirName, resolved.getAbsolutePath());
        }
        String propertyName = "user.dir." + dirName;
        String path = resolved.getAbsolutePath() + File.separator;
        System.setProperty(propertyName, path);
        log.info(
                "{}{} path: {}", dirName.substring(0, 1).toUpperCase(), dirName.substring(1), path);
    }

    private static synchronized File serviceHome() {
        if (serviceHome == null) {
            serviceHome = resolveServiceHome();
        }
        return serviceHome;
    }

    private static File resolveServiceHome() {
        String base = System.getProperty("user.dir");

        if (new File(base, CONF_DIR).exists()) {
            return new File(base);
        }
        if (new File(base, SERVICE_DIR + File.separator + CONF_DIR).exists()) {
            return new File(base, SERVICE_DIR);
        }
        File parent = new File(base).getParentFile();
        if (parent != null && new File(parent, CONF_DIR).exists()) {
            return parent;
        }
        return new File(base);
    }

    public static String getConfDir() {
        return System.getProperty("user.dir.conf");
    }

    public static String getLogDir() {
        return System.getProperty("user.dir.logs");
    }

    public static String getDataDir() {
        return System.getProperty("user.dir.data");
    }

    public static String getTempDir() {
        return System.getProperty("user.dir.temp");
    }

    public static String getUploadDir() {
        return System.getProperty("user.dir.upload");
    }

    /** Service home directory that contains {@code conf}, {@code sql}, {@code data} and so on. */
    public static File getServiceHome() {
        return serviceHome();
    }
}
