## POST http://localhost:42613/echo → 200 OK

### Request Headers
```
POST http://localhost:42613/echo
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
date: Sat, 14 Mar 2026 10:03:35 GMT
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
curl 'http://localhost:42613/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
