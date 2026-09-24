## DELETE http://localhost:42431/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 24 Sep 2026 22:45:27 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:42431/echo' \
  -X DELETE
```
