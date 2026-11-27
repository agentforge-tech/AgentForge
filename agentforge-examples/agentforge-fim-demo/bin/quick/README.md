# FIM Demo 快速部署

```bash
cp deploy.conf.example deploy.conf
# 填写密码或配置 SSH 免密
./deploy.sh
```

- `build.sh`：构建 fat jar。
- `push.sh`：上传到服务器并重启。
- `deploy.sh`：构建 + 部署。
- `restart.sh status|start|stop|restart`：远程管理服务。

远程目录包含 jar、`start.sh`、`app.pid` 和 `logs/console.log`。
