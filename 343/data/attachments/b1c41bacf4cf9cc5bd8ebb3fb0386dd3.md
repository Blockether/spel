## POST http://localhost:41463/echo → 200 OK

### Request Headers
```
POST http://localhost:41463/echo
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
date: Mon, 02 Mar 2026 08:37:50 GMT
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
curl 'http://localhost:41463/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
