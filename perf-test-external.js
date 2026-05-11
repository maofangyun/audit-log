const http = require('http');
const fs = require('fs');

// --- 配置项 ---
const TARGET_URL = 'http://localhost:8080/test/user/update';
const CONCURRENCY = 20; 
const TEST_DURATION_MS = 60000; // 测试持续 60 秒
const DATA_FILE = process.argv[2] || 'test-data.json'; // 从命令行获取文件名，默认 test-data.json

// --- 加载外部数据 ---
if (!fs.existsSync(DATA_FILE)) {
    console.error(`❌ 错误: 找不到文件 '${DATA_FILE}'`);
    console.log(`用法: node perf-test-external.js <json_file_path>`);
    process.exit(1);
}

console.log(`📖 正在从 ${DATA_FILE} 加载测试数据...`);
const fileContent = fs.readFileSync(DATA_FILE, 'utf8');

// 构造与原脚本一致的 payload 结构
const payload = JSON.stringify({ 
    id: "perf-test-id", 
    name: fileContent, 
    age: 25 
});

const payloadBuffer = Buffer.from(payload);
const actualSizeMB = payloadBuffer.length / (1024 * 1024);

const options = {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': payloadBuffer.length
    }
};

let successCount = 0;
let errorCount = 0;
let activeRequests = 0; 
let isStopping = false; 
const startTime = Date.now();

function sendRequest() {
    if (Date.now() - startTime > TEST_DURATION_MS) {
        isStopping = true;
        return;
    }

    activeRequests++;
    const req = http.request(TARGET_URL, options, (res) => {
        res.on('data', () => { });
        res.on('end', () => {
            if (res.statusCode === 200) successCount++;
            else errorCount++;
            
            activeRequests--;
            sendRequest(); 
        });
    });

    req.on('error', (e) => {
        errorCount++;
        activeRequests--;
        sendRequest();
    });

    req.write(payloadBuffer);
    req.end();
}

console.log(`🔥 开始压力测试 (基于外部文件)...`);
console.log(`📊 负载大小: ${actualSizeMB.toFixed(2)} MB | 并发: ${CONCURRENCY} | 时长: ${TEST_DURATION_MS/1000}s`);
console.log('--------------------------------------------');

for (let i = 0; i < CONCURRENCY; i++) {
    sendRequest();
}

const timer = setInterval(() => {
    const elapsed = (Date.now() - startTime) / 1000;
    const throughput = (successCount * actualSizeMB / elapsed).toFixed(2);
    
    if (!isStopping) {
        process.stdout.write(`⏱ 运行中: ${elapsed.toFixed(1)}s | 成功: ${successCount} | 失败: ${errorCount} | 吞吐量: ${throughput} MB/s\r`);
    } else {
        process.stdout.write(`⏳ 正在收尾 (等待 ${activeRequests} 个在途请求)... 成功: ${successCount}\r`);
    }

    if (isStopping && activeRequests === 0) {
        clearInterval(timer);
        const finalElapsed = (Date.now() - startTime) / 1000;
        console.log('\n\n✅ 测试结束!');
        console.log(`总计成功: ${successCount}`);
        console.log(`总计失败: ${errorCount}`);
        console.log(`实际总时长: ${finalElapsed.toFixed(2)}s`);
        console.log(`平均吞吐量: ${(successCount * actualSizeMB / finalElapsed).toFixed(2)} MB/s`);
    }
}, 500);
