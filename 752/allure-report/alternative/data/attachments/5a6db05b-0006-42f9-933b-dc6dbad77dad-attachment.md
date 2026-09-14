## DELETE http://localhost:37925/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 14 Sep 2026 21:31:30 GMT
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
curl 'http://localhost:37925/echo' \
  -X DELETE
```
