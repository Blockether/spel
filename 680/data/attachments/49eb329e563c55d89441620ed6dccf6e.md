## PATCH http://localhost:45645/echo → 200 OK

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
date: Tue, 04 Aug 2026 05:22:27 GMT
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
curl 'http://localhost:45645/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
