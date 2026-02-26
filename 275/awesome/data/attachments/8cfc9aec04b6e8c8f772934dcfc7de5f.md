## POST http://localhost:39797/echo → 200 OK

### Request Headers
```
POST http://localhost:39797/echo
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
date: Thu, 26 Feb 2026 15:45:01 GMT
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
curl 'http://localhost:39797/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
