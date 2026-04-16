## DELETE http://localhost:44257/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 16 Apr 2026 08:07:08 GMT
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
curl 'http://localhost:44257/echo' \
  -X DELETE
```
