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

// 1) Backend jar (normalized name so backend.js never tracks versions)
fs.copyFileSync(jarPath, path.join(outDir, 'backend.jar'));
console.log(`Copied ${jarPath} -> ${path.join(outDir, 'backend.jar')}`);

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
