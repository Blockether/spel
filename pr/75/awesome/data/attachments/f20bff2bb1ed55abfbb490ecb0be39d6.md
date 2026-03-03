## POST http://localhost:40473/echo → 200 OK

### Request Headers
```
POST http://localhost:40473/echo
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
date: Tue, 03 Mar 2026 09:00:37 GMT
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
curl 'http://localhost:40473/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
