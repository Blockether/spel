## POST http://localhost:39953/echo → 200 OK

### Request Headers
```
POST http://localhost:39953/echo
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
date: Thu, 09 Apr 2026 19:02:00 GMT
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
curl 'http://localhost:39953/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
