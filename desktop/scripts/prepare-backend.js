// Prepare the bundled backend for packaged desktop builds (Epic #18 T2).
//
// Copies the Spring Boot fat jar and jlinks a trimmed Java runtime into
// desktop/bundled/<target>/, which electron-builder picks up via
// extraResources (see package.json, "bundled/${os}-${arch}").
//
// Usage:
//   node scripts/prepare-backend.js --jar <path-to-backend.jar> --out <target dir> [--jmods <jmods dir>]
//
// --jmods points jlink at another platform's jmods for cross-arch linking
// (e.g. building the mac-x64 runtime on an arm64 runner); defaults to the
// jmods of the JDK at JAVA_HOME.

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// Module set = `jdeps --print-module-deps` over the backend fat jar, widened
// to java.se (aggregator), plus modules jdeps cannot see (reflection/JPMS
// services): jdk.charsets (GBK/GB18030 for Chinese documents), jdk.crypto.*
// (TLS), jdk.localedata (zh locales), jdk.zipfs, jdk.unsupported (Netty/
// Hibernate Unsafe usage). Verified empirically: backend boots on this
// runtime with the desktop profile and zero ClassNotFound/UnsatisfiedLink.
const JLINK_MODULES = [
    'java.se',
    'jdk.charsets',
    'jdk.crypto.cryptoki',
    'jdk.crypto.ec',
    'jdk.httpserver',
    'jdk.jfr',
    'jdk.localedata',
    'jdk.management',
    'jdk.naming.dns',
    'jdk.net',
    'jdk.security.auth',
    'jdk.unsupported',
    'jdk.xml.dom',
    'jdk.zipfs'
].join(',');

function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--jar') args.jar = argv[++i];
        else if (argv[i] === '--out') args.out = argv[++i];
        else if (argv[i] === '--jmods') args.jmods = argv[++i];
    }
    return args;
}

const args = parseArgs(process.argv.slice(2));
if (!args.jar || !args.out) {
    console.error('Usage: node scripts/prepare-backend.js --jar <backend.jar> --out <target dir> [--jmods <jmods dir>]');
    process.exit(1);
}

const javaHome = process.env.JAVA_HOME;
if (!javaHome) {
    console.error('JAVA_HOME must point to a JDK (jlink is required).');
    process.exit(1);
}

const jarPath = path.resolve(args.jar);
if (!fs.existsSync(jarPath)) {
    console.error(`Backend jar not found: ${jarPath} (run "mvn -DskipTests package" in backend/ first)`);
    process.exit(1);
}

const outDir = path.resolve(args.out);
const jmodsDir = path.resolve(args.jmods || path.join(javaHome, 'jmods'));
if (!fs.existsSync(jmodsDir)) {
    console.error(`jmods directory not found: ${jmodsDir}`);
    process.exit(1);
}

fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });

// 1) Backend split layout (增量更新设计 §4.1)：fat jar 拆成 backend/lib/*.jar
// （全部依赖，约 360MB，只随大版本变）+ backend/app.jar（业务代码，约 1.5MB，
// 小版本补丁只发它）。启动改为 java -cp "app.jar:lib/*" com.checkba.CheckbaApplication
// （backend-service.js javaLaunchArgs），不再依赖 Spring Boot 嵌套 classloader。
const jarTool = path.join(javaHome, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar');
// 工作目录放在 outDir 同盘：CI Windows runner 的 os.tmpdir() 在 C 盘而 workspace
// 在 D 盘，跨盘 renameSync 直接 EXDEV（run 31081406715）
const work = fs.mkdtempSync(path.join(path.dirname(outDir), 'backend-split-'));
console.log(`Extracting fat jar (BOOT-INF) -> ${work}`);
execFileSync(jarTool, ['-x', '-f', jarPath, 'BOOT-INF/lib', 'BOOT-INF/classes'], { cwd: work, stdio: 'inherit' });

const backendDir = path.join(outDir, 'backend');
fs.mkdirSync(backendDir, { recursive: true });
fs.renameSync(path.join(work, 'BOOT-INF', 'lib'), path.join(backendDir, 'lib'));

const classesDir = path.join(work, 'BOOT-INF', 'classes');
const mainClassFile = path.join(classesDir, 'com', 'checkba', 'CheckbaApplication.class');
if (!fs.existsSync(mainClassFile)) {
    console.error(`Main class missing after extract: ${mainClassFile}`);
    process.exit(1);
}
const appJar = path.join(backendDir, 'app.jar');
execFileSync(jarTool, ['-c', '-f', appJar, '-C', classesDir, '.'], { stdio: 'inherit' });
fs.rmSync(work, { recursive: true, force: true });
const libCount = fs.readdirSync(path.join(backendDir, 'lib')).length;
console.log(`Backend split: app.jar ${(fs.statSync(appJar).size / 1048576).toFixed(1)}MB + lib/ ${libCount} jars`);

// 2) Trimmed runtime
const jlink = path.join(javaHome, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
const jreDir = path.join(outDir, 'jre');
console.log(`jlink (modules from ${jmodsDir}) -> ${jreDir}`);
execFileSync(jlink, [
    '--module-path', jmodsDir,
    '--add-modules', JLINK_MODULES,
    '--strip-debug',
    '--no-man-pages',
    '--no-header-files',
    '--compress', 'zip-6',
    '--output', jreDir
], { stdio: 'inherit' });

console.log(`Bundled backend ready at: ${outDir}`);
