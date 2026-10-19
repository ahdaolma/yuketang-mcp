# 雨课堂学习桥 MCP

通过微信扫码授权，让支持 MCP 的助手读取自己账号已开放的课程与课件。

**状态：原型。协议和模拟接口测试可运行；尚未用真实账号联调。当前执行环境无法连通雨课堂。**

## 目前包含什么

| 工具 | 功能 |
| --- | --- |
| `login_start` | 开始微信扫码登录并返回二维码 |
| `login_status` | 显示登录状态和账号名，不输出凭据 |
| `list_courses` | 读取接口返回的课程批次 |
| `list_course_activities` | 分页读取课程活动，每页 50 条 |
| `list_live_classes` | 查看正在上课的课程 |
| `list_slides` | 读取已知 presentation_id 的课件目录 |
| `read_slide` | 返回单页课件图片与服务器提供的文字 |

不需要单独购买大模型 API；图片理解由调用 MCP 的助手完成。

## 必须知道的未完成部分

- 微信扫码、账号权限、你学校的接口版本都还需要实测。项目使用学生端网络接口，不是官方开放 API，平台更新可能使其失效。
- 活动里的 `classroom_id`、`lesson_id`、`courseware_id` 不能直接当成 `presentation_id`。只有拿到确切的课件标识才能读页；当前尚未完成各类历史课件和活动到课件标识的统一解析。
- 某些课件要求课堂令牌，本版遇到权限错误会停止；尚未实现课堂授权连接。
- **尚未实现老师当前页自动同步，也无法获知你微信屏幕正在看哪一页。**
- 本地 STDIO MCP 是可测试的目标；HTTP 仅监听 `127.0.0.1:8765`，用于本机调试。尚无远程部署、OAuth 和 ChatGPT 云端插件接入配置。不能仅上传 GitHub 就直接连到手机聊天。
- 微信扫码成功一般获得同账号会话，但不同区域服务会话独立，需要选对版本。

## Windows 启动

安装 Python 3.11 或 3.12，下载并解压项目。在项目文件夹打开 PowerShell：

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe server.py login --region standard
```

程序会在电脑上打开二维码图片。用手机微信扫描并确认，成功后显示账号名称。
也可以由 MCP 客户端调用 `login_start` 完成登录。

| 微信入口名称 | region 参数 |
| --- | --- |
| 雨课堂 | `standard` |
| 荷塘雨课堂 | `hetang` |
| 长江雨课堂 | `changjiang` |
| 黄河雨课堂 | `huanghe` |

例如荷塘版：`server.py login --region hetang`。网络请求使用系统直连；不自动读取代理环境变量。

## 本地 MCP 客户端

支持 STDIO 的客户端可使用以下配置（将两个路径替换成你的实际绝对路径）：

```json
{
  "mcpServers": {
    "yuketang": {
      "command": "D:\\yuketang-mcp\\.venv\\Scripts\\python.exe",
      "args": ["D:\\yuketang-mcp\\server.py", "serve", "--region", "standard"]
    }
  }
}
```

对于使用 TOML 配置的本地 Codex：

```toml
[mcp_servers.yuketang]
command = 'D:\yuketang-mcp\.venv\Scripts\python.exe'
args = ['D:\yuketang-mcp\server.py', 'serve', '--region', 'standard']
```

启动后先调用 `login_status` 核对账号，再 `list_courses` 和 `list_course_activities`。
若活动返回明确的 `presentation_id`，可调用 `list_slides`，然后按目录的 `page` 调用 `read_slide`。
`page` 从 1 开始，按返回顺序编号；`source_index` 另行保留，避免把服务端索引当页码。

## 登录数据

登录记录默认保存在当前系统用户目录 `.yuketang-mcp/<region>.json`，不在项目目录。
可用 `YKT_STATE_DIR` 改路径。不要将该目录、二维码或 Cookie 上传 GitHub。
文件在类 Unix 系统以 0600 权限写入；Windows 使用当前用户目录权限，未实现凭据库加密。
删除该 JSON 可移除本地保存的登录记录；这不等于注销服务器上的会话。

图片下载单独请求，不附带账号 Cookie；图片域名有限制，遇到未适配的域名会报错。

## 验证

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

测试覆盖模拟微信登录、会话落盘、权限错误、接口字段变化、页码、MCP 图像返回，以及真实 STDIO 握手和工具调用。
这些测试不等于已通过雨课堂真实账号测试。

## 后续联调顺序

1. 在用户设备上选择实际雨课堂版本，微信扫码并核对账号。
2. 验证课程活动字段及课件标识，补齐历史课件详情读取。
3. 按实际权限验证课堂连接及页码事件，再实现当前页同步。
4. 验证远程认证、部署和账号隔离后，配置 ChatGPT 插件或远程连接。

## 设计参考

以下为接口行为研究来源；本项目未打包这些项目的实现代码：

- https://github.com/infstellar/RainClassroomAssistant （微信登录、课件 fetch、正在上课接口）
- https://github.com/0error0warning/stu-mcp （课程及课程活动接口）
- https://github.com/modelcontextprotocol/python-sdk/tree/v1.x （本版固定使用 MCP Python SDK 1.x）
- https://learn.chatgpt.com/docs/extend/mcp （本地与云端接入方式）
