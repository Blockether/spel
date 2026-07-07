## PATCH http://localhost:41093/echo → 200 OK

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
date: Tue, 07 Jul 2026 10:38:23 GMT
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
curl 'http://localhost:41093/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
