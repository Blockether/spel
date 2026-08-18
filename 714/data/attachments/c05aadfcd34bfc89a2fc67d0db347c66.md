## PATCH http://localhost:44901/echo → 200 OK

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
date: Tue, 18 Aug 2026 06:15:50 GMT
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
curl 'http://localhost:44901/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
