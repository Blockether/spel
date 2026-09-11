## PATCH http://localhost:36037/echo → 200 OK

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
date: Fri, 11 Sep 2026 04:21:27 GMT
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
curl 'http://localhost:36037/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
