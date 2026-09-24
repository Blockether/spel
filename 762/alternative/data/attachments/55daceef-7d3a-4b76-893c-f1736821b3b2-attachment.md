## PATCH http://localhost:42431/echo → 200 OK

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
date: Thu, 24 Sep 2026 22:45:27 GMT
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
curl 'http://localhost:42431/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
