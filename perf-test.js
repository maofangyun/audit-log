const http = require('http');

const TARGET_URL = 'http://localhost:8080/test/user/update';
const CONCURRENCY = 20; 
const TEST_DURATION_MS = 60000; // 测试持续 60 秒

/**
 * 生成约 targetSizeMB 的真实 JSON 格式字符串
 */
function generateLargeJsonString(targetSizeMB) {
    const targetSizeBytes = targetSizeMB * 1024 * 1024;
    const itemTemplate = {
        id: 10001,
        guid: "d9e8c7b6-a5b4-c3d2-e1f0-9a8b7c6d5e4f",
        isActive: true,
        balance: "$3,141.59",
        name: "Performance Test User",
        company: "AUDIT-LOG-SERVICES",
        about: "This is a long description used to fill space and simulate a real-world JSON object with significant text content.",
        registered: "2026-04-24T10:00:00.000Z",
        tags: ["audit", "log", "performance", "test"],
        metadata: { appVersion: "1.2.3", environment: "production" }
    };
    
    const singleItemStr = JSON.stringify(itemTemplate);
    const count = Math.ceil(targetSizeBytes / (singleItemStr.length + 1));
    const items = [];
    for (let i = 0; i < count; i++) {
        items.push({ ...itemTemplate, id: i });
    }
    return JSON.stringify(items);
}

console.log("🚀 准备测试数据...");
const bigData = generateLargeJsonString(10);
const payload = JSON.stringify({ id: "perf-test-id", name: bigData, age: 25 });
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
let activeRequests = 0; // 当前正在进行的请求数
let isStopping = false; // 是否已到达测试时长，准备停止
const startTime = Date.now();

/**
 * 发送压测请求的核心方法
 */
function sendRequest() {
    // 如果已经超时且没有正在进行的请求，则彻底结束
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
            sendRequest(); // 递归发起下一个
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

console.log(`🔥 开始压力测试...`);
console.log(`📊 负载大小: ${actualSizeMB.toFixed(2)} MB | 并发: ${CONCURRENCY} | 时长: ${TEST_DURATION_MS/1000}s`);
console.log('--------------------------------------------');

for (let i = 0; i < CONCURRENCY; i++) {
    sendRequest();
}

// 每秒打印一次进度
const timer = setInterval(() => {
    const elapsed = (Date.now() - startTime) / 1000;
    const throughput = (successCount * actualSizeMB / elapsed).toFixed(2);
    
    if (!isStopping) {
        process.stdout.write(`⏱ 运行中: ${elapsed.toFixed(1)}s | 成功: ${successCount} | 失败: ${errorCount} | 吞吐量: ${throughput} MB/s\r`);
    } else {
        process.stdout.write(`⏳ 正在收尾 (等待 ${activeRequests} 个在途请求)... 成功: ${successCount}\r`);
    }

    // 只有当时间到了且所有在途请求都已返回，才停止定时器并退出
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
