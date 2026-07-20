## PATCH http://localhost:40791/echo → 200 OK

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
date: Mon, 20 Jul 2026 09:17:13 GMT
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
curl 'http://localhost:40791/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
