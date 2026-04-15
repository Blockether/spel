## PATCH http://localhost:37065/echo → 200 OK

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Wed, 15 Apr 2026 10:40:05 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:37065/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
