## PATCH http://localhost:40229/echo → 200 OK

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
date: Sat, 11 Apr 2026 11:28:55 GMT
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
curl 'http://localhost:40229/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
