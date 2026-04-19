const http = require('http');

const TARGET_URL = 'http://localhost:8080/test/user/update';
const CONCURRENCY = 10; // 并发数（注意：1MB大包，并发不宜开启过高，否则会瞬间挤爆带宽）
const TEST_DURATION_MS = 30000; // 测试持续 30 秒

// 生成约 1.1MB 的随机大字符串
const bigData = 'X'.repeat(1024);

const payload = JSON.stringify({
    id: "perf-test-id",
    name: bigData, // 填充超大内容
    age: 25
});

const options = {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload)
    }
};

let successCount = 0;
let errorCount = 0;
const startTime = Date.now();

/**
 * 发送压测请求的核心方法
 * 该方法采用异步递归模式，以维持恒定的并发压力
 */
function sendRequest() {
    // 【同步】检查测试时长，超时则停止递归调用
    if (Date.now() - startTime > TEST_DURATION_MS) return;

    // 【异步/非阻塞】发起请求，该方法会立即返回一个请求对象 req
    // 此处的 (res) => { ... } 回调函数是异步的，在收到响应头后由事件循环触发
    const req = http.request(TARGET_URL, options, (res) => {
        // 【异步监听】每当接收到一小块响应数据时触发，必须监听以确保响应流流动
        res.on('data', () => { });

        // 【异步监听】当整个 HTTP 响应接收完成时触发
        res.on('end', () => {
            successCount++; // 【同步】成功计数
            sendRequest();  // 【异步递归】发起下一个请求，保持并发数
        });
    });

    // 【异步监听】当网络连接、DNS 或数据传输发生错误时触发
    req.on('error', (e) => {
        errorCount++;   // 【同步】错误计数
        sendRequest();  // 【异步递归】出错后也继续尝试，保持并发压力
    });

    // 【半异步】将 1MB 负载写入内核缓冲区，系统会择机通过网卡发出
    req.write(payload);

    // 【异步触发】标记请求发送完成
    req.end();
}

console.log(`🚀 开始压力测试... 负载大小: ~1.1MB, 并发数: ${CONCURRENCY}`);
for (let i = 0; i < CONCURRENCY; i++) {
    sendRequest();
}

// 每秒打印一次进度
const timer = setInterval(() => {
    const elapsed = (Date.now() - startTime) / 1000;
    console.log(`⏱ 已耗时: ${elapsed.toFixed(1)}s | 成功: ${successCount} | 失败: ${errorCount} | 吞吐量: ${(successCount * 1.1 / elapsed).toFixed(2)} MB/s`);

    if (elapsed >= TEST_DURATION_MS / 1000) {
        clearInterval(timer);
        console.log('\n✅ 测试结束!');
        console.log(`总计成功: ${successCount}`);
        console.log(`平均吞吐量: ${(successCount * 1.1 / (TEST_DURATION_MS / 1000)).toFixed(2)} MB/s`);
    }
}, 1000);
