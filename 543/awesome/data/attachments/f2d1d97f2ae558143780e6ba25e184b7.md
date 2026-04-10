## POST http://localhost:42969/echo → 200 OK

### Request Headers
```
POST http://localhost:42969/echo
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
date: Fri, 10 Apr 2026 03:58:37 GMT
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
curl 'http://localhost:42969/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
