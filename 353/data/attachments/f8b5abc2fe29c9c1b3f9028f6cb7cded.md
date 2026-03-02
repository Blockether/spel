## POST http://localhost:33017/echo → 200 OK

### Request Headers
```
POST http://localhost:33017/echo
```

### Request Body
```json
{
  "name": "Eve",
  "action": "create"
}
```

### Response Headers
```
content-length: 72
content-type: application/json
date: Mon, 02 Mar 2026 12:13:16 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Eve",
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:33017/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
