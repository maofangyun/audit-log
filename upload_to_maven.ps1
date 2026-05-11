# =================================================================
# Maven 私服批量上传脚本
# =================================================================

# --- 配置项 ---
$REPO_URL = "http://your-nexus-url/repository/maven-releases/"
$REPO_ID = "nexus-releases" # 对应 settings.xml 中的 <server><id>
$LIB_DIR = ".\lib"

# --- 检查环境 ---
if (!(Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 mvn 命令，请确保 Maven 已安装并配置在 Path 中。"
    exit
}

# --- 开始处理 ---
$jars = Get-ChildItem -Path $LIB_DIR -Filter "*.jar" | Where-Object { $_.Name -notmatch "-sources.jar$" }

foreach ($jar in $jars) {
    $jarPath = $jar.FullName
    $baseName = $jar.BaseName
    $sourceJarPath = Join-Path $LIB_DIR "$($baseName)-sources.jar"
    
    Write-Host "----------------------------------------------------"
    Write-Host "正在处理: $($jar.Name)"

    # 尝试从 JAR 包中提取 pom.properties 以获取 GAV 信息
    # 路径通常为: META-INF/maven/<groupId>/<artifactId>/pom.properties
    $tempDir = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ([Guid]::NewGuid().ToString()))
    
    try {
        # 使用 tar 或 jar 命令提取属性文件 (Windows 10+ 自带 tar)
        & tar -xf "$jarPath" -C "$tempDir" --wildcards "META-INF/maven/*/pom.properties" 2>$null
        
        $propFile = Get-ChildItem -Path $tempDir -Recurse -Filter "pom.properties" | Select-Object -First 1
        
        if ($propFile) {
            $props = ConvertFrom-StringData (Get-Content $propFile.FullName -Raw)
            $groupId = $props.groupId
            $artifactId = $props.artifactId
            $version = $props.version
        } else {
            Write-Warning "未能在 JAR 中找到 pom.properties，尝试从文件名解析 (非标准)..."
            # 简单备选逻辑：假设文件名格式为 artifactId-version.jar
            if ($jar.Name -match "^(.+)-([\d\.]+(?:-[\w\.]+)?)\.jar$") {
                $groupId = "temp.manual.upload" # 无法确定时使用的默认分组
                $artifactId = $Matches[1]
                $version = $Matches[2]
            } else {
                Write-Error "无法解析 $($jar.Name) 的坐标，跳过。"
                continue
            }
        }

        # 构建 Maven 部署命令
        $mvnCmd = "mvn deploy:deploy-file `
            -Durl=$REPO_URL `
            -DrepositoryId=$REPO_ID `
            -Dfile=`"$jarPath`" `
            -DgroupId=$groupId `
            -DartifactId=$artifactId `
            -Dversion=$version `
            -Dpackaging=jar `
            -DgeneratePom=true"

        # 如果存在源码包，添加 sources 附件
        if (Test-Path $sourceJarPath) {
            $mvnCmd += " -Dsources=`"$sourceJarPath`""
            Write-Host "检测到源码包，将一并上传。"
        }

        Write-Host "执行部署: $groupId:$artifactId:$version"
        Invoke-Expression $mvnCmd

    } catch {
        Write-Error "处理 $($jar.Name) 时发生异常: $_"
    } finally {
        Remove-Item -Path $tempDir -Recurse -Force
    }
}

Write-Host "----------------------------------------------------"
Write-Host "上传任务结束。"
