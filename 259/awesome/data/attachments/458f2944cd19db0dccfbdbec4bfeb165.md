## POST http://localhost:37035/echo → 200 OK

### Request Headers
```
POST http://localhost:37035/echo
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
date: Wed, 25 Feb 2026 16:09:46 GMT
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
curl 'http://localhost:37035/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
