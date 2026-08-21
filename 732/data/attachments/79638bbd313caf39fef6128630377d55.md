## PATCH http://localhost:42325/echo → 200 OK

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
date: Fri, 21 Aug 2026 03:01:52 GMT
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
curl 'http://localhost:42325/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
