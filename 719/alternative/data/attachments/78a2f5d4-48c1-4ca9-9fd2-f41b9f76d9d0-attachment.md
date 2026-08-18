## PATCH http://localhost:43677/echo → 200 OK

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
date: Tue, 18 Aug 2026 09:35:12 GMT
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
curl 'http://localhost:43677/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
