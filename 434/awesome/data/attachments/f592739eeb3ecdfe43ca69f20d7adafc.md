## POST http://localhost:46543/echo → 200 OK

### Request Headers
```
POST http://localhost:46543/echo
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
date: Sat, 07 Mar 2026 22:35:03 GMT
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
curl 'http://localhost:46543/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
