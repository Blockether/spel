## PUT http://localhost:34187/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Wed, 22 Apr 2026 17:51:25 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:34187/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
